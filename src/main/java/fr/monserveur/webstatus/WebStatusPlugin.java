package fr.monserveur.webstatus;

import com.google.inject.Inject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Plugin Velocity "WebStatusVelocity".
 *
 * Expose un petit serveur HTTP (endpoint /status) renvoyant un JSON détaillé
 * du réseau : nombre de joueurs en ligne au total, répartition par
 * sous-serveur, état en ligne/hors ligne. Le backend Node du site vitrine
 * interroge cette route à la place du simple ping Minecraft si tu veux des
 * données plus riches (répartition par serveur notamment).
 *
 * Sécurité : une clé API (header "X-Api-Key") est requise, configurable
 * dans config.yml. Le serveur HTTP écoute par défaut uniquement sur
 * 127.0.0.1 : si ton backend Node tourne sur une autre machine, mets
 * bind-address à 0.0.0.0 dans config.yml ET restreins l'accès au port
 * choisi via ton pare-feu (ne l'expose jamais librement sur Internet).
 */
@Plugin(
        id = "webstatus-velocity",
        name = "WebStatusVelocity",
        version = "1.0.0",
        description = "Expose un endpoint HTTP JSON avec le statut détaillé du réseau (joueurs en ligne, répartition par sous-serveur) pour l'afficher en direct sur le site web.",
        authors = {"Toi"}
)
public class WebStatusPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private HttpServer httpServer;
    private String apiKey;

    @Inject
    public WebStatusPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        PluginConfig config = loadConfig();
        this.apiKey = config.apiKey;

        if ("change-moi".equals(this.apiKey)) {
            logger.warn("[WebStatusVelocity] ⚠️  Pense à changer 'api-key' dans plugins/webstatus-velocity/config.yml !");
        }

        try {
            httpServer = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
            httpServer.createContext("/status", new StatusHandler());
            httpServer.setExecutor(null);
            httpServer.start();
            logger.info("[WebStatusVelocity] Endpoint de statut démarré sur http://" + config.bindAddress + ":" + config.port + "/status");
        } catch (IOException e) {
            logger.error("[WebStatusVelocity] Impossible de démarrer le serveur HTTP : " + e.getMessage());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (httpServer != null) httpServer.stop(0);
    }

    /**
     * Petit chargeur de config.yml "fait maison" (pas de dépendance YAML
     * tierce nécessaire côté Velocity) : on ne lit que 3 clés simples au
     * format "cle: valeur".
     */
    private PluginConfig loadConfig() {
        PluginConfig config = new PluginConfig();
        try {
            if (!Files.exists(dataDirectory)) Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.yml");
            if (!Files.exists(file)) {
                try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) {
                        Files.copy(in, file);
                    } else {
                        Files.writeString(file, config.toYaml());
                    }
                }
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int idx = trimmed.indexOf(':');
                if (idx < 0) continue;
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                switch (key) {
                    case "port":
                        try {
                            config.port = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                        }
                        break;
                    case "bind-address":
                        config.bindAddress = value;
                        break;
                    case "api-key":
                        config.apiKey = value;
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            logger.error("Erreur de chargement de config.yml : " + e.getMessage());
        }
        return config;
    }

    private static class PluginConfig {
        int port = 8181;
        String bindAddress = "127.0.0.1";
        String apiKey = "change-moi";

        String toYaml() {
            return "# Port d'écoute du mini serveur HTTP exposant le statut du réseau\n"
                    + "port: " + port + "\n\n"
                    + "# Adresse d'écoute : 127.0.0.1 si le backend Node tourne sur la même machine\n"
                    + "# que le proxy Velocity (recommandé). Mets 0.0.0.0 seulement si le backend est\n"
                    + "# ailleurs, et protège alors ce port avec un pare-feu.\n"
                    + "bind-address: " + bindAddress + "\n\n"
                    + "# Clé secrète à renseigner aussi côté backend (MC_PLUGIN_API_KEY dans .env)\n"
                    + "api-key: " + apiKey + "\n";
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String providedKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
            if (apiKey == null || !apiKey.equals(providedKey)) {
                sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }

            StringBuilder perServer = new StringBuilder();
            boolean first = true;
            for (RegisteredServer server : proxy.getAllServers()) {
                if (!first) perServer.append(',');
                first = false;
                int playerCount = server.getPlayersConnected().size();
                perServer.append('"').append(escape(server.getServerInfo().getName())).append('"')
                        .append(':').append(playerCount);
            }

            int online = proxy.getPlayerCount();
            int max = proxy.getConfiguration().getShowMaxPlayers();

            String motd = "";
            Optional<Component> motdComponent = Optional.ofNullable(proxy.getConfiguration().getMotd());
            if (motdComponent.isPresent()) {
                motd = PlainTextComponentSerializer.plainText().serialize(motdComponent.get());
            }

            String json = "{"
                    + "\"online\":true,"
                    + "\"players\":{\"online\":" + online + ",\"max\":" + max + "},"
                    + "\"servers\":{" + perServer + "},"
                    + "\"motd\":\"" + escape(motd) + "\","
                    + "\"version\":\"" + escape(proxy.getVersion().getVersion()) + "\""
                    + "}";

            sendJson(exchange, 200, json);
        }

        private String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("§[0-9a-fk-or]", "");
        }

        private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
