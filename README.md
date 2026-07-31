# 🌐 WebStatusVelocity

**WebStatusVelocity** est un plugin Velocity qui expose un serveur HTTP léger renvoyant des informations détaillées sur votre réseau Minecraft sous forme de JSON. Parfait pour afficher le statut de votre serveur en temps réel sur votre site web !

![Velocity](https://img.shields.io/badge/Velocity-3.3.0-blue)
![Java](https://img.shields.io/badge/Java-11+-orange)
![License](https://img.shields.io/badge/License-MIT-green)

---

## ✨ Fonctionnalités

- 📊 **Statistiques en temps réel** — Nombre de joueurs en ligne et maximum
- 🖥️ **Répartition par serveur** — Détail des joueurs connectés sur chaque sous-serveur (lobby, survival, etc.)
- 📝 **MOTD dynamique** — Affiche le message du jour de votre serveur
- 🔄 **Version du proxy** — Information sur la version de Velocity utilisée
- 🔒 **Sécurité intégrée** — Protection par clé API (header `X-Api-Key`)
- 🎨 **Léger et rapide** — Serveur HTTP intégré, aucune dépendance externe

---

## 🚀 Installation

1. Téléchargez la dernière release depuis la page [Releases](../../releases/latest)
2. Placez le fichier `webstatus-velocity-1.0.1.jar` dans le dossier `plugins` de votre proxy Velocity
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
```

---

## 📡 Utilisation

### Requête

```bash
curl -H "X-Api-Key: votre-cle-api" http://localhost:8181/status
```

### Réponse JSON

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

### Intégration avec votre site web

Le plugin est conçu pour fonctionner avec un backend web (Node.js, PHP, Python, etc.) qui interroge régulièrement cet endpoint pour afficher le statut du serveur en temps réel.

---

## 🔧 Compilation depuis les sources

### Prérequis

- Java 11 ou supérieur
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

---

## 📄 Licence

Ce projet est sous licence MIT. Voir le fichier [LICENSE](LICENSE) pour plus de détails.

---

⭐ N'hésitez pas à laisser une étoile si ce plugin vous est utile !
