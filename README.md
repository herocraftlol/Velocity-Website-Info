# WebStatusVelocity

Plugin Velocity permettant d'exposer un endpoint HTTP JSON avec le statut détaillé du réseau Minecraft (joueurs en ligne, répartition par sous-serveur) pour l'afficher en direct sur un site web.

## Fonctionnalités

- **Endpoint HTTP `/status`** : Renvoie un JSON détaillé du réseau Minecraft
- **Joueurs en ligne** : Nombre total de joueurs connectés
- **Répartition par serveur** : Détail des joueurs connectés sur chaque sous-serveur
- **MOTD** : Message du jour du serveur
- **Version** : Version du proxy Velocity
- **Sécurité** : Clé API requise (header `X-Api-Key`)

## Installation

1. Téléchargez la dernière release depuis la page [Releases](../../releases)
2. Placez le fichier `webstatus-velocity.jar` dans le dossier `plugins` de votre proxy Velocity
3. Redémarrez le proxy
4. Configurez la clé API dans `plugins/webstatus-velocity/config.yml`

## Configuration

```yaml
# Port d'écoute du mini serveur HTTP exposant le statut du réseau
port: 8181

# Adresse d'écoute : 127.0.0.1 si le backend Node tourne sur la même machine
bind-address: 127.0.0.1

# Clé secrète à renseigner aussi côté backend
api-key: change-moi
```

## Utilisation

Interrogez l'endpoint avec la clé API :

```bash
curl -H "X-Api-Key: votre-cle" http://localhost:8181/status
```

Réponse JSON :

```json
{
  "online": true,
  "players": {"online": 5, "max": 100},
  "servers": {"lobby": 2, "survival": 3},
  "motd": "Bienvenue sur le serveur!",
  "version": "3.3.0"
}
```

## Compilation

```bash
mvn clean package
```

Le fichier JAR sera généré dans `target/webstatus-velocity.jar`.

## Licence

MIT
