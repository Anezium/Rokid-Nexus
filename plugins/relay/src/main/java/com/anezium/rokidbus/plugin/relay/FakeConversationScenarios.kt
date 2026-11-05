package com.anezium.rokidbus.plugin.relay

/** Fictional exchanges for wearer-led reading tests; null names the device's user. */
internal object FakeConversationScenarios {
    data class Message(val sender: String?, val text: String)
    data class Thread(val title: String, val messages: List<Message>, val group: Boolean = false)

    val threads = listOf(
        Thread("Mika et José", listOf(
            Message("Mika", "On se retrouve samedi pour dîner ?"),
            Message(null, "Oui ! Je vous propose 19 h 30 chez moi."),
            Message("José", "Je finis le travail plus tard. On peut plutôt faire 20 h ?"),
            Message("Mika", "20 h, ça me va. Il faut prévoir un plat végétarien pour José."),
            Message("José", "Oui, merci ! J'apporte les boissons."),
            Message(null, "Alors samedi à 20 h chez moi. Je prépare le plat végétarien."),
            Message("Mika", "J'apporte le dessert. Tu peux m'envoyer l'adresse avant vendredi ?"),
            Message("José", "Et dis-nous si on peut se garer devant chez toi."),
        ), group = true),
        Thread("Jean", listOf(
            Message("Jean", "Tu peux m'aider à terminer le dossier d'inscription ?"),
            Message(null, "Oui, qu'est-ce qu'il manque ?"),
            Message("Jean", "Il me faut une copie signée du formulaire avant demain midi."),
            Message(null, "Je prépare la copie ce soir."),
            Message("Jean", "Tu peux aussi vérifier que ton adresse postale est à jour dans le dossier ?"),
            Message("Jean", "Et renvoie-moi la référence du paiement, juste le numéro, pas le justificatif."),
            Message(null, "D'accord, je t'enverrai le formulaire et la référence demain matin."),
            Message("Jean", "Merci ! Préviens-moi quand tu auras tout envoyé."),
        )),
        Thread("Léa", listOf(
            Message("Léa", "Ça te dit d'aller au cinéma vendredi ?"),
            Message(null, "Oui, quelle séance ?"),
            Message("Léa", "Celle de 20 h 15, salle 6. J'ai déjà pris les deux billets."),
            Message(null, "Parfait, on se retrouve devant à 20 h."),
            Message("Léa", "Ça marche, je garde les billets sur mon téléphone."),
        )),
    )
}
