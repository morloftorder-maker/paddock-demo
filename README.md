# Paddock — démo

Réseau social du sport motorisé : pilotes, organisateurs, commanditaires et partisans.

## Obtenir l'APK pour un téléphone Android

Chaque envoi sur la branche `main` compile automatiquement l'application (onglet **Actions**).
Le fichier `Paddock-demo.apk` est ensuite publié dans **Releases**.

1. Sur le téléphone, ouvrir la page **Releases** de ce dépôt.
2. Toucher `Paddock-demo.apk` pour le télécharger, puis l'ouvrir.
3. Si Android le demande, autoriser l'installation depuis cette source.

## Contenu

- `app/` : application Android (WebView) qui contient la démo dans `app/src/main/assets/index.html`.
- `web/` : la même démo en version web installable (PWA), à héberger sur n'importe quel site HTTPS.
- `.github/workflows/build-apk.yml` : compilation automatique de l'APK.

Les comptes créés dans la démo sont enregistrés seulement sur le téléphone. Paiements simulés.

## Mises à jour sans réinstaller

L'app Android vérifie `version.json` sur la branche `main` à chaque ouverture.
Si le numéro `web` est plus grand que celui de la version installée, elle télécharge `web/index.html`
et propose « Redémarrer » pour l'appliquer. Les comptes de la démo sont conservés.

Pour publier une mise à jour : modifier `web/index.html` (et la même copie dans `app/src/main/assets/`),
puis augmenter le numéro dans `version.json` et `app/src/main/assets/version.json`.
Il faut réinstaller l'APK seulement si le code Android (`app/src/main/java`) change.
