package fr.monserveur.webstatus;

import com.google.inject.Inject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin Velocity "WebStatusVelocity".
 *
 * Expose deux endpoints HTTP JSON pour le site vitrine :
 *
 *  - /status       : statut en direct du réseau (joueurs en ligne, répartition par
 *                    sous-serveur) — inchangé depuis la version précédente.
 *  - /connections  : statistiques de connexion (nombre de joueurs par jour, version du
 *                    jeu utilisée, Java vs Bedrock, pays/région) sur une plage de temps
 *                    donnée — voir ConnectionsHandler.
 *
 * Le suivi des connexions se fait ici, sur le proxy Velocity, et pas sur chaque serveur
 * Paper, car c'est le SEUL point de passage obligé de TOUTES les connexions, quel que
 * soit le sous-serveur rejoint ensuite.
 *
 * IMPORTANT — ce qui est possible et ce qui ne l'est PAS :
 *  - Version du jeu (1.8, 1.21...) : oui, Velocity connaît le protocole exact du client.
 *  - Java vs Bedrock : oui, mais UNIQUEMENT si Geyser+Floodgate sont installés sur ce
 *    proxy (détecté automatiquement par réflexion, aucune dépendance obligatoire). Sans
 *    Floodgate, ce réseau n'accepte de toute façon que des clients Java : tout le monde
 *    est alors compté "JAVA" par définition.
 *  - Pays/région : approximatif, par géolocalisation de l'IP via l'API publique
 *    ip-api.com (gratuite, sans clé). L'IP elle-même n'est JAMAIS stockée : seul le
 *    pays/la région résolus sont conservés. Désactivable (voir "geo-enabled" dans
 *    config.yml) pour les administrateurs qui préfèrent ne pas envoyer les IP des
 *    joueurs à un service tiers.
 *  - Launcher utilisé (officiel, TLauncher, Prism...) : IMPOSSIBLE. Le protocole
 *    Minecraft ne transmet jamais cette information — aucun moyen fiable de la
 *    récupérer, quel que soit le plugin installé.
 *
 * Sécurité : une clé API (header "X-Api-Key") est requise, configurable dans
 * config.yml. Le serveur HTTP écoute par défaut uniquement sur 127.0.0.1.
 */
@Plugin(
        id = "webstatus-velocity",
        name = "WebStatusVelocity",
        version = "1.1.0",
        description = "Expose des endpoints HTTP JSON avec le statut du réseau et des statistiques de connexion (versions, Java/Bedrock, pays) pour le site web.",
        authors = {"Toi"}
)
public class WebStatusPlugin {

    private static final Pattern PRIVATE_IP = Pattern.compile(
            "^(127\\.|10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|::1|0:0:0:0:0:0:0:1)");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpServer httpServer;
    private PluginConfig config;
    private Path connectionsDir;

    /** Cache "ce plugin (Floodgate) est-il présent" : résolu une seule fois. */
    private Boolean floodgatePresent = null;

    @Inject
    public WebStatusPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.config = loadConfig();

        if ("change-moi".equals(config.apiKey)) {
            logger.warn("[WebStatusVelocity] ⚠️  Pense à changer 'api-key' dans plugins/webstatus-velocity/config.yml !");
        }

        try {
            connectionsDir = dataDirectory.resolve("connections");
            Files.createDirectories(connectionsDir);
        } catch (IOException e) {
            logger.error("[WebStatusVelocity] Impossible de créer le dossier 'connections' : " + e.getMessage());
        }

        try {
            httpServer = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
            httpServer.createContext("/status", new StatusHandler());
            httpServer.createContext("/connections", new ConnectionsHandler());
            httpServer.setExecutor(null);
            httpServer.start();
            logger.info("[WebStatusVelocity] Endpoints démarrés sur http://" + config.bindAddress + ":" + config.port + " (/status, /connections)");
            if (!config.trackConnections) {
                logger.info("[WebStatusVelocity] Suivi des connexions désactivé (track-connections: false dans config.yml).");
            } else if (isFloodgatePresent()) {
                logger.info("[WebStatusVelocity] Floodgate détecté : les joueurs Bedrock seront correctement identifiés.");
            } else {
                logger.info("[WebStatusVelocity] Floodgate non détecté : tous les joueurs seront comptés comme Java (normal si ce réseau n'accepte pas Bedrock).");
            }
        } catch (IOException e) {
            logger.error("[WebStatusVelocity] Impossible de démarrer le serveur HTTP : " + e.getMessage());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (httpServer != null) httpServer.stop(0);
    }

    // ================= SUIVI DES CONNEXIONS =================

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (config == null || !config.trackConnections) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        boolean bedrock = isBedrockPlayer(uuid);

        // Pour un joueur Bedrock, Geyser traduit ses paquets en paquets Java avant qu'ils
        // n'atteignent Velocity : la version "Java" vue par le proxy ne serait donc PAS sa
        // vraie version Bedrock (ex: afficherait "1.21.4" au lieu de "1.21.50"/"26.1.2").
        // Floodgate expose la vraie version native du client Bedrock, on l'utilise en priorité.
        String versionName;
        if (bedrock) {
            String bedrockVersion = getBedrockVersion(uuid);
            versionName = bedrockVersion != null ? bedrockVersion
                    : (player.getProtocolVersion() != null ? player.getProtocolVersion().getVersionIntroducedIn() : "Inconnue");
        } else {
            versionName = player.getProtocolVersion() != null ? player.getProtocolVersion().getVersionIntroducedIn() : "Inconnue";
        }

        String ip = null;
        if (player.getRemoteAddress() != null && player.getRemoteAddress().getAddress() != null) {
            ip = player.getRemoteAddress().getAddress().getHostAddress();
        }

        long timestamp = System.currentTimeMillis();
        String platform = bedrock ? "BEDROCK" : "JAVA";

        if (!config.geoEnabled || ip == null || PRIVATE_IP.matcher(ip).find()) {
            recordConnection(timestamp, uuid, username, versionName, platform, "Inconnu", "Inconnu");
            return;
        }

        geolocateAsync(ip, (country, region) ->
                recordConnection(timestamp, uuid, username, versionName, platform, country, region));
    }

    /**
     * Détecte si ce joueur est connecté via Bedrock (Geyser+Floodgate), par réflexion
     * pure : AUCUNE dépendance Floodgate n'est nécessaire dans le pom.xml. Si Floodgate
     * n'est pas installé sur ce proxy, renvoie toujours false (= Java), ce qui est
     * correct puisqu'un réseau sans Floodgate n'accepte de toute façon que du Java.
     */
    private boolean isBedrockPlayer(UUID uuid) {
        if (!isFloodgatePresent()) return false;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(instance, uuid);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isFloodgatePresent() {
        if (floodgatePresent == null) {
            try {
                Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgatePresent = true;
            } catch (Throwable t) {
                floodgatePresent = false;
            }
        }
        return floodgatePresent;
    }

    /**
     * Récupère la vraie version du client Bedrock (ex: "1.21.50", ou un futur format
     * "26.1.2") via l'API Floodgate, par réflexion pure (aucune dépendance compile-time).
     * Renvoie null si Floodgate n'est pas installé, si le joueur n'est pas Bedrock, ou en
     * cas d'erreur — l'appelant retombe alors sur la version Java traduite par Geyser.
     */
    private String getBedrockVersion(UUID uuid) {
        if (!isFloodgatePresent()) return null;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = apiClass.getMethod("getInstance").invoke(null);
            Object floodgatePlayer = apiClass.getMethod("getPlayer", UUID.class).invoke(instance, uuid);
            if (floodgatePlayer == null) return null;

            Class<?> playerClass = Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer");
            Object version = playerClass.getMethod("getVersion").invoke(floodgatePlayer);
            return version != null ? version.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Géolocalise une IP de façon asynchrone via l'API publique gratuite ip-api.com
     * (aucune clé requise, usage non-commercial). L'IP n'est utilisée que pour cet
     * appel, jamais écrite sur disque : seul le résultat (pays/région) est conservé.
     * En cas d'échec (timeout, service indisponible...), renvoie "Inconnu"/"Inconnu"
     * plutôt que de bloquer ou de faire échouer la connexion du joueur.
     */
    private void geolocateAsync(String ip, java.util.function.BiConsumer<String, String> onResult) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://ip-api.com/json/" + ip + "?fields=status,country,regionName"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        String body = response.body();
                        String status = extractJsonField(body, "status");
                        if (!"success".equals(status)) {
                            onResult.accept("Inconnu", "Inconnu");
                            return;
                        }
                        String country = extractJsonField(body, "country");
                        String region = extractJsonField(body, "regionName");
                        onResult.accept(
                                country != null && !country.isBlank() ? country : "Inconnu",
                                region != null && !region.isBlank() ? region : "Inconnu"
                        );
                    })
                    .exceptionally(e -> {
                        onResult.accept("Inconnu", "Inconnu");
                        return null;
                    });
        } catch (Exception e) {
            onResult.accept("Inconnu", "Inconnu");
        }
    }

    private String extractJsonField(String json, String field) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private void recordConnection(long timestamp, UUID uuid, String username, String version, String platform, String country, String region) {
        String line = timestamp + "|" + uuid + "|" + escapePipe(username) + "|" + escapePipe(version) + "|" + platform
                + "|" + escapePipe(country) + "|" + escapePipe(region);
        LocalDate date = LocalDate.now(ZoneId.systemDefault());
        Path file = connectionsDir.resolve(date + ".log");
        try {
            Files.writeString(file, line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.error("[WebStatusVelocity] Impossible d'écrire dans le journal de connexions : " + e.getMessage());
        }
    }

    /** Les pseudos Minecraft ne contiennent jamais '|', mais pays/région pourraient (très rare) : on protège quand même. */
    private String escapePipe(String s) {
        return s == null ? "" : s.replace("|", " ");
    }

    // ================= CONFIG =================

    /**
     * Petit chargeur de config.yml "fait maison" (pas de dépendance YAML
     * tierce nécessaire côté Velocity) : on ne lit que quelques clés simples au
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
                    case "track-connections":
                        config.trackConnections = Boolean.parseBoolean(value);
                        break;
                    case "geo-enabled":
                        config.geoEnabled = Boolean.parseBoolean(value);
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
        boolean trackConnections = true;
        boolean geoEnabled = true;

        String toYaml() {
            return "# Port d'écoute du mini serveur HTTP exposant le statut du réseau + les connexions\n"
                    + "port: " + port + "\n\n"
                    + "# Adresse d'écoute : 127.0.0.1 si le backend Node tourne sur la même machine\n"
                    + "# que le proxy Velocity (recommandé). Mets 0.0.0.0 seulement si le backend est\n"
                    + "# ailleurs, et protège alors ce port avec un pare-feu.\n"
                    + "bind-address: " + bindAddress + "\n\n"
                    + "# Clé secrète à renseigner aussi côté backend (MC_PLUGIN_API_KEY dans .env)\n"
                    + "api-key: " + apiKey + "\n\n"
                    + "# Suivi des connexions (nombre de joueurs/jour, versions, Java/Bedrock, pays)\n"
                    + "# pour l'onglet admin \"Statistiques\" du site. Ne stocke jamais l'IP elle-même,\n"
                    + "# seulement le pays/la région résolus si geo-enabled est activé.\n"
                    + "track-connections: " + trackConnections + "\n\n"
                    + "# Géolocalisation des connexions par IP (via l'API publique ip-api.com, gratuite,\n"
                    + "# sans clé). Désactive si tu préfères ne jamais envoyer les IP des joueurs à un\n"
                    + "# service tiers (le pays/la région resteront alors \"Inconnu\").\n"
                    + "geo-enabled: " + geoEnabled + "\n";
        }
    }

    // ================= ENDPOINT /status (inchangé) =================

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String providedKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
            if (config.apiKey == null || !config.apiKey.equals(providedKey)) {
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

    // ================= ENDPOINT /connections =================

    /**
     * Agrège le journal de connexions (voir {@link #recordConnection}) sur une plage de
     * dates donnée : ?range=today|week|alltime (défaut alltime), ou ?from=AAAA-MM-JJ&to=AAAA-MM-JJ.
     * Renvoie : le nombre de joueurs (uniques + total des connexions) par jour, la
     * répartition par version du jeu, la répartition Java/Bedrock, et le classement des
     * pays/régions.
     */
    private class ConnectionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String providedKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
            if (config.apiKey == null || !config.apiKey.equals(providedKey)) {
                sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }

            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            LocalDate[] range = resolveRange(query);

            List<LocalDate> days = new ArrayList<>();
            for (LocalDate d = range[0]; !d.isAfter(range[1]); d = d.plusDays(1)) days.add(d);

            List<String> dailyJson = new ArrayList<>();
            Map<String, Integer> versionCounts = new HashMap<>();
            Map<String, Integer> platformCounts = new HashMap<>();
            Map<String, Map<String, Integer>> countryRegionCounts = new LinkedHashMap<>();

            for (LocalDate day : days) {
                Path file = connectionsDir.resolve(day + ".log");
                java.util.Set<UUID> uniquePlayers = new java.util.HashSet<>();
                int totalLogins = 0;

                if (Files.exists(file)) {
                    for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        if (rawLine.isBlank()) continue;
                        String[] parts = rawLine.split("\\|", -1);
                        if (parts.length < 7) continue;

                        try {
                            UUID uuid = UUID.fromString(parts[1]);
                            String version = parts[3];
                            String platform = parts[4];
                            String country = parts[5];
                            String region = parts[6];

                            uniquePlayers.add(uuid);
                            totalLogins++;
                            versionCounts.merge(version, 1, Integer::sum);
                            platformCounts.merge(platform, 1, Integer::sum);
                            countryRegionCounts
                                    .computeIfAbsent(country, k -> new HashMap<>())
                                    .merge(region, 1, Integer::sum);
                        } catch (IllegalArgumentException ignored) {
                            // ligne corrompue, on l'ignore
                        }
                    }
                }

                dailyJson.add("{\"date\":\"" + day + "\",\"uniquePlayers\":" + uniquePlayers.size()
                        + ",\"totalLogins\":" + totalLogins + "}");
            }

            StringBuilder json = new StringBuilder();
            json.append("{\"daily\":[").append(String.join(",", dailyJson)).append("],");

            json.append("\"versions\":{");
            appendCountMap(json, versionCounts);
            json.append("},");

            json.append("\"platforms\":{");
            appendCountMap(json, platformCounts);
            json.append("},");

            json.append("\"countries\":[");
            List<String> countryEntries = new ArrayList<>();
            for (Map.Entry<String, Map<String, Integer>> countryEntry : countryRegionCounts.entrySet()) {
                for (Map.Entry<String, Integer> regionEntry : countryEntry.getValue().entrySet()) {
                    countryEntries.add("{\"country\":\"" + escapeJson(countryEntry.getKey())
                            + "\",\"region\":\"" + escapeJson(regionEntry.getKey())
                            + "\",\"count\":" + regionEntry.getValue() + "}");
                }
            }
            json.append(String.join(",", countryEntries));
            json.append("]}");

            sendJson(exchange, 200, json.toString());
        }

        private void appendCountMap(StringBuilder json, Map<String, Integer> counts) {
            List<String> entries = new ArrayList<>();
            counts.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                    .forEach(e -> entries.add("\"" + escapeJson(e.getKey()) + "\":" + e.getValue()));
            json.append(String.join(",", entries));
        }

        /**
         * Détermine la plage de dates demandée :
         *  - ?from=AAAA-MM-JJ&to=AAAA-MM-JJ → plage précise (prioritaire si présente)
         *  - ?range=today                    → aujourd'hui uniquement
         *  - ?range=week                      → 7 derniers jours glissants
         *  - ?range=alltime (ou absent)       → depuis le plus ancien journal existant
         */
        private LocalDate[] resolveRange(Map<String, String> query) {
            String from = query.get("from");
            String to = query.get("to");
            if (from != null && to != null) {
                try {
                    LocalDate f = LocalDate.parse(from);
                    LocalDate t = LocalDate.parse(to);
                    return f.isAfter(t) ? new LocalDate[]{ t, f } : new LocalDate[]{ f, t };
                } catch (Exception ignored) {
                    // dates invalides : on retombe sur "alltime" plutôt que planter
                }
            }

            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            String rangeParam = query.getOrDefault("range", "alltime").toLowerCase();
            return switch (rangeParam) {
                case "today" -> new LocalDate[]{ today, today };
                case "week" -> new LocalDate[]{ today.minusDays(6), today };
                default -> new LocalDate[]{ oldestLogDate(today), today };
            };
        }

        /** Date du plus ancien journal de connexions existant (borne "alltime"), ou aujourd'hui s'il n'y en a aucun. */
        private LocalDate oldestLogDate(LocalDate fallback) {
            try (var stream = Files.list(connectionsDir)) {
                return stream
                        .map(p -> p.getFileName().toString().replace(".log", ""))
                        .map(name -> {
                            try {
                                return LocalDate.parse(name);
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .filter(java.util.Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElse(fallback);
            } catch (IOException e) {
                return fallback;
            }
        }

        private Map<String, String> parseQuery(String rawQuery) {
            Map<String, String> params = new HashMap<>();
            if (rawQuery == null || rawQuery.isBlank()) return params;
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
            return params;
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
