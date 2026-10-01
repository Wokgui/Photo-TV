# Photo TV

Photo TV est un diaporama conçu pour Android TV / Google TV, avec une interface télécommande et une présentation inspirée d'un cadre photo haut de gamme.

## Sources prises en charge

- Google Photos via un export Google Takeout : lecture des JSON et récupération des noms d'albums depuis `albumData.title` lorsqu'ils sont présents.
- Dossiers locaux et sélection manuelle de photos / vidéos.
- WebDAV.
- SMB / NAS.
- Formats image courants, GIF, HEIC / HEIF, AVIF et vidéos compatibles Android.

## Fonctions principales

- Diaporama plein écran avec navigation précédente / suivante et reprise de l'état.
- 16 transitions, Ken Burns, transitions adaptatives selon le média et les performances, modes Remplir / Adapter / Original / Fond flouté et Mosaïque 2 / 3 / 4.
- Recadrage intelligent local avec détection de visage / sujet et cache persistant du point d'intérêt.
- Photos, GIF et vidéos.
- Date, heure et météo.
- Métadonnées : titre, album, date, lieu, appareil, objectif, focale, ouverture, ISO, exposition, dimensions et orientation lorsque disponibles.
- Lecture des métadonnées Google Takeout avec repli sur EXIF.
- Sélection de plusieurs albums et fusion des doublons exacts ou visuellement quasi identiques.
- Favoris, masquage temporaire ou permanent, recherche et tri d'albums.
- Sélection intelligente optionnelle : qualité, anti-répétition et souvenirs.
- Diaporama intelligent autonome : adapte la sélection au moment de la journée et injecte ponctuellement des mosaïques sans modifier les réglages manuels.
- Détection locale de scènes (portrait, nuit, mer / ciel, nature, intérieur, ville) pour diversifier le diaporama et les mosaïques.
- Pré-analyse locale progressive des scènes et du recadrage intelligent, mise en cache entre les redémarrages.
- Page « Intelligence et performances » : centralise les automatismes, leur état et une explication claire de leur rôle ; permet d'activer le diaporama autonome, la sélection intelligente et la pré-analyse sans chercher dans plusieurs menus.
- Vue Souvenirs dédiée.
- Règles par album selon le jour, l'heure, la durée et la transition.
- Éditeur visuel des textes : police, taille, couleur, position, alignement, ombre et visibilité.
- Presets de disposition Standard, Minimal, Cinéma et Horloge.
- Mode nuit programmable.
- Télécommande smartphone sur le réseau local avec lien secret, aperçus authentifiés, gestes de balayage, favori visible et aperçu plein écran.
- Cache réseau, lecture hors ligne des éléments déjà téléchargés, suivi de santé / latence par source et rafraîchissement automatique.
- Préchargement et budgets mémoire adaptatifs jusqu'aux écrans 4K.
- Diagnostic intégré : mémoire, FPS, jank, décodage, cache, index, réseau et fichiers illisibles.
- Export / import des réglages.
- Économiseur d'écran Android DreamService.
- Démarrage automatique optionnel.

## Robustesse

Photo TV conserve le dernier index connu d'une source réseau pendant une panne temporaire, isole l'état hors ligne source par source et réessaie les erreurs réseau transitoires au lieu de classer immédiatement le média comme définitivement illisible.

Le projet inclut des tests JVM, des tests Android instrumentés jusqu'à 50 000 médias synthétiques, des stress tests jusqu'à 100 000 éléments, une régression visuelle du preview Web, des tests de navigation Android TV, des smoke tests 720p / 1080p / 4K et un marathon renforcé sur émulateur Android TV.

## Aperçu Web

L'aperçu GitHub Pages reproduit les quatre écrans principaux et permet de tester une grande partie des interactions à la souris ou au clavier :

https://wokgui.github.io/Photo-TV/

## Construction

Le workflow GitHub Actions vérifie le preview Web, exécute les tests JVM et Android, contrôle les captures de régression, compile l'APK et vérifie sa signature.

L'artefact produit s'appelle `Photo-TV-APK` et contient l'APK générique, un APK nommé `Photo-TV-v<version>.apk`, les informations de version / signature et un changelog récent. Après validation complète d'une version poussée sur `main`, la CI crée également automatiquement le tag et la GitHub Release correspondants.

## Remarque sur Google Photos

Photo TV ne prétend pas connaître un nom d'album exact lorsqu'il n'est pas fourni par Google. En mode Takeout, le nom exact provient du JSON de l'album. Si cette information manque, l'interface l'indique explicitement au lieu d'inventer un nom.
