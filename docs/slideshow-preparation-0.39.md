# Photos TV 0.39 — préparation des prochaines photos

La prochaine transition utilise une photo déjà décodée en pleine qualité, et conserve le même choix que le préchargement en mode aléatoire. La photo actuelle et la vraie suivante restent protégées dans le cache, y compris lors d’une demande Android de réduction de mémoire. La photo précédente reste disponible pendant le fondu.

Le préchargement prépare plusieurs prochaines photos, selon la taille des images affichées et le budget de mémoire. Deux tâches de préparation peuvent avancer en parallèle. Les téléchargements Drive de photos différentes ne partagent plus un verrou global ; une vignette lente ne bloque donc plus toutes les photos suivantes.

La lecture des photos dans une archive Takeout regroupe l’en-tête et les premiers octets, puis utilise des plages bornées à 4 Mio. Les contrôles de taille et de CRC restent actifs. Une photo stockée de 2 Mio est préparée en une seule lecture distante.

Si le réseau ne fournit exceptionnellement pas la suivante à temps, la photo actuelle reste affichée jusqu’à ce que la suivante soit prête. La file aléatoire et l’historique ne sont consommés qu’au changement réel. Aucun téléchargement réseau ne peut être garanti instantané à froid ; le fonctionnement normal repose sur cette préparation pendant les photos précédentes.

Vérification : tests de lecture ZIP, contrôle des limites de requêtes, des photos corrompues, de la préparation en ordre aléatoire et de la pression mémoire ; transitions mesurées sur de vraies photos Drive au rythme normal de dix secondes. Les rapports privés propres à chaque appareil accompagnent la sauvegarde complète.
