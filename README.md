# Photo TV

Photo TV est un diaporama conçu pour Android TV / Google TV, avec une interface télécommande et une présentation inspirée d'un cadre photo haut de gamme.

## Sources prises en charge

- Google Photos via un export Google Takeout : Photo TV lit les JSON et récupère les noms d'albums depuis `albumData.title` lorsqu'ils sont présents.
- Dossiers locaux : les noms de dossiers servent de regroupement.
- Sélection manuelle de photos et vidéos : l'album Google Photos d'origine n'est alors pas garanti.

## Fonctions principales

- Diaporama plein écran avec navigation précédente / suivante.
- 16 transitions, Ken Burns, modes Remplir / Adapter / Original / Fond flouté.
- Photos, GIF et vidéos.
- Date, heure et météo.
- Métadonnées : titre, album, date, lieu, appareil photo, dimensions et orientation.
- Lecture des métadonnées Google Takeout avec repli sur EXIF quand disponible.
- Sélection de plusieurs albums et fusion d'une même photo présente dans plusieurs albums.
- Favoris, masquage temporaire ou permanent, recherche et tri d'albums.
- Règles par album selon le jour, l'heure, la durée et la transition.
- Éditeur visuel des textes : police, taille, couleur, position, alignement, ombre et visibilité.
- Presets de disposition Standard, Minimal, Cinéma et Horloge.
- Export / import des réglages.
- Économiseur d'écran Android DreamService.
- Démarrage automatique optionnel.

## Aperçu Web

L'aperçu GitHub Pages reproduit les quatre écrans principaux et permet de tester une grande partie des interactions à la souris ou au clavier :

https://wokgui.github.io/Photo-TV/

## Construction

Le workflow GitHub Actions vérifie la syntaxe du preview Web, lance les tests JVM puis compile l'APK debug.

L'artefact produit s'appelle `Photo-TV-APK`.

## Remarque sur Google Photos

Photo TV ne prétend pas connaître un nom d'album exact lorsqu'il n'est pas fourni par Google. En mode Takeout, le nom exact provient du JSON de l'album. Si cette information manque, l'interface l'indique explicitement au lieu d'inventer un nom.
