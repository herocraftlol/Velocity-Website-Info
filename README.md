# 🌐 WebStatusVelocity

**WebStatusVelocity** est un plugin Velocity qui expose un serveur HTTP léger renvoyant des informations détaillées sur votre réseau Minecraft sous forme de JSON. Parfait pour afficher le statut de votre serveur en temps réel sur votre site web et suivre les statistiques de connexion de vos joueurs !

![Velocity](https://img.shields.io/badge/Velocity-3.3.0-blue)
![Java](https://img.shields.io/badge/Java-17+-orange)
![License](https://img.shields.io/badge/License-MIT-green)

---

## ✨ Fonctionnalités

### Endpoints HTTP JSON

Le plugin expose **deux endpoints** protégés par clé API :

#### `/status` — Statut en temps réel
- 📊 **Joueurs en ligne** — Nombre actuel et maximum
- 🖥️ **Répartition par serveur** — Détail des joueurs sur chaque sous-serveur (lobby, survival, etc.)
- 📝 **MOTD dynamique** — Message du jour de votre serveur
- 🔄 **Version du proxy** — Information sur la version de Velocity utilisée

#### `/connections` — Statistiques de connexion
- 📅 **Historique quotidien** — Nombre de joueurs uniques et connexions totales par jour
- 🎮 **Versions du jeu** — Répartition des joueurs par version Minecraft (1.8, 1.21, etc.)
- 🖥️ **Plateforme** — Distinction Java vs Bedrock (si Geyser+Floodgate installés)
- 🌍 **Géolocalisation** — Pays et région des connexions (via ip-api.com, sans stocker les IPs)

### Autres fonctionnalités
- 🔒 **Sécurité intégrée** — Protection par clé API (header `X-Api-Key`)
- 🎨 **Léger et rapide** — Serveur HTTP intégré, aucune dépendance externe
- 📝 **Suivi des connexions** — Journalisation quotidienne des connexions avec statistiques détaillées
- 🔍 **Détection Floodgate** — Identification automatique des joueurs Bedrock

---

## 🚀 Installation

1. Téléchargez la dernière release depuis la page [Releases](../../releases/latest)
2. Placez le fichier `webstatus-velocity-1.1.0.jar` dans le dossier `plugins` de votre proxy Velocity
3. Redémarrez votre proxy Velocity
4. Modifiez la clé API dans `plugins/webstatus-velocity/config.yml`

---

## ⚙️ Configuration

Le fichier `config.yml` se trouve dans `plugins/webstatus-velocity/`

```yaml
# Port d'écoute du serveur HTTP (par défaut: 8181)
port: 8181

# Adresse d'écoute:
# - 127.0.0.1 : recommandé si le backend et le proxy sont sur la même machine
# - 0.0.0.0 : si le backend est sur une autre machine (protégez par un pare-feu !)
bind-address: 127.0.0.1

# Clé API secrète - DOIT correspondre à celle de votre backend
api-key: change-moi

# Suivi des connexions (versions, Java/Bedrock, pays)
track-connections: true

# Géolocalisation via ip-api.com (désactivez si vous préférez ne pas envoyer les IPs)
geo-enabled: true
```

---

## 📡 Utilisation

### Endpoint `/status`

```bash
curl -H "X-Api-Key: votre-cle-api" http://localhost:8181/status
```

**Réponse JSON :**
```json
{
  "online": true,
  "players": {
    "online": 42,
    "max": 100
  },
  "servers": {
    "lobby": 15,
    "survival": 20,
    "creative": 7
  },
  "motd": "§bBienvenue sur §aMonServeur§b !",
  "version": "3.3.0"
}
```

### Endpoint `/connections`

```bash
# Aujourd'hui uniquement
curl -H "X-Api-Key: votre-cle-api" "http://localhost:8181/connections?range=today"

# 7 derniers jours
curl -H "X-Api-Key: votre-cle-api" "http://localhost:8181/connections?range=week"

# Plage de dates précise
curl -H "X-Api-Key: votre-cle-api" "http://localhost:8181/connections?from=2024-01-01&to=2024-01-31"

# Tout l'historique
curl -H "X-Api-Key: votre-cle-api" "http://localhost:8181/connections"
```

**Réponse JSON :**
```json
{
  "daily": [
    {"date": "2024-01-15", "uniquePlayers": 42, "totalLogins": 67},
    {"date": "2024-01-16", "uniquePlayers": 38, "totalLogins": 55}
  ],
  "versions": {"1.21": 50, "1.20.4": 20, "1.8": 5},
  "platforms": {"JAVA": 65, "BEDROCK": 10},
  "countries": [
    {"country": "France", "region": "Île-de-France", "count": 30},
    {"country": "Belgique", "region": "Wallonie", "count": 15}
  ]
}
```

### Intégration avec votre site web

Le plugin est conçu pour fonctionner avec un backend web (Node.js, PHP, Python, etc.) qui interroge régulièrement ces endpoints pour afficher le statut du serveur en temps réel et les statistiques de connexion.

---

## 🔧 Compilation depuis les sources

### Prérequis

- Java 17 ou supérieur
- Maven 3.6+

### Commandes

```bash
# Cloner le dépôt
git clone https://github.com/herocraftlol/Velocity-Website-Info.git

# Entrer dans le dossier
cd Velocity-Website-Info

# Compiler
mvn clean package

# Le fichier JAR sera dans target/webstatus-velocity.jar
```

---

## 📋 Changelog

### Version 1.1.0
- ✨ **Nouvel endpoint `/connections`** — Statistiques de connexion détaillées
- 📊 **Suivi des versions** — Répartition des joueurs par version Minecraft
- 🖥️ **Détection Java/Bedrock** — Identification des joueurs Bedrock (avec Geyser+Floodgate)
- 🌍 **Géolocalisation** — Pays et région des connexions via ip-api.com
- ⚙️ **Nouvelles options de config** — `track-connections` et `geo-enabled`
- 📝 **Journalisation quotidienne** — Fichiers de logs dans `plugins/webstatus-velocity/connections/`

### Version 1.0.1
- Correction de bugs et améliorations de stabilité
- Nettoyage du code source

### Version 1.0.0
- Version initiale
- Endpoint `/status` avec statistiques joueurs
- Support MOTD et version du proxy
- Sécurité par clé API

---

## 🛡️ Sécurité

Pour protéger votre endpoint :
- ⚠️ **Ne laissez jamais `bind-address` sur `0.0.0.0`** sans pare-feu
- 🔑 **Utilisez une clé API forte** et différente de `change-moi`
- 🌐 **Limitez l'accès** au port HTTP uniquement depuis votre serveur web
- 📍 **Géolocalisation optionnelle** — Désactivez `geo-enabled` si vous préférez ne pas envoyer les IPs à ip-api.com

---

## 📄 Licence

Ce projet est sous licence MIT. Voir le fichier [LICENSE](LICENSE) pour plus de détails.

---

⭐ N'hésitez pas à laisser une étoile si ce plugin vous est utile !
