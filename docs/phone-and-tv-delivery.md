# Installation sur smartphone et télévision

À chaque nouvelle installation de Photos TV sur le smartphone, installer le même APK sur la TCL et vérifier la version sur les deux appareils. Comparer aussi le SHA-256 du paquet réellement installé à celui de l'APK livré. Utiliser `tools/install-phone-and-tv.ps1` ; adapter l'adresse de la télé si elle change.

Le script vérifie l'accès aux deux appareils avant de commencer. Il installe par-dessus le paquet existant, sans désinstallation ni effacement des données. Si un appareil est inaccessible ou une installation échoue, ne pas annoncer que les deux appareils sont à jour.

Vérifier ensuite le chargement, l'accès aux albums et les réglages sur les appareils réels. Préserver les données et préférences lors des tests. Le réglage « Intensité du fondu » est dans Réglages → Diaporama, vaut 100 % par défaut pour conserver le rendu antérieur, et est inclus dans la sauvegarde/export/import des paramètres.
