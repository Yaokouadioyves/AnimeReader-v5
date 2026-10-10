package com.animereader.internal

/**
 * Prompts shared by the vision backends (Gemini, Mistral), written for French/English scans:
 * the "original" must come back clean — normal casing instead of ALL CAPS, French accents
 * restored, hyphen-cut words glued — because for a French scan read in French it IS what the
 * voice reads. Bubbles already in the target language get an empty translation, and scan-group
 * credits/watermarks are dropped. Android-free so it is JVM-unit-testable.
 */
object VisionPrompts {

    private const val READ_RULES =
        "Recopie le texte en corrigeant les erreurs de lecture, en casse normale (pas tout en " +
            "majuscules), en rétablissant les accents français manquants et en recollant les mots " +
            "coupés par un tiret en fin de ligne. Garde les noms propres tels quels. "

    private fun translateRules(targetLanguage: String) =
        "Traduis-le en $targetLanguage de façon naturelle, fidèle au ton et au registre (tutoiement " +
            "ou vouvoiement cohérent avec le contexte). Si le texte est déjà en $targetLanguage, " +
            "laisse « translation » vide. Ignore les crédits et filigranes des équipes de scan " +
            "(sites web, « Trad : », « Check : »…) : chaînes vides pour eux. "

    fun single(targetLanguage: String, dialogueContext: String): String =
        context(dialogueContext) +
            "Cette image est une bulle de scan (manga, BD ou webtoon). " + READ_RULES +
            translateRules(targetLanguage) +
            "Réponds STRICTEMENT en JSON {\"original\":\"…\",\"translation\":\"…\"}. " +
            "Aucun texte lisible -> chaînes vides."

    fun batch(count: Int, targetLanguage: String, dialogueContext: String): String =
        context(dialogueContext) +
            "Voici $count images numérotées de 1 à $count dans l'ordre d'envoi ; chacune est une " +
            "bulle de scan (manga, BD ou webtoon), dans l'ordre de lecture. Pour CHAQUE image : " +
            READ_RULES + translateRules(targetLanguage) +
            "Réponds STRICTEMENT en JSON {\"bubbles\":[{\"n\":1,\"original\":\"…\",\"translation\":\"…\"}, …]} " +
            "avec exactement $count éléments, « n » étant le numéro de l'image. Image illisible -> chaînes vides."

    /** The last dialogue lines, injected for coherent pronouns/tone across bubbles. */
    private fun context(dialogueContext: String): String =
        if (dialogueContext.isBlank()) ""
        else "Contexte — répliques précédentes du dialogue (original => traduction), pour garder " +
            "pronoms, ton et vocabulaire cohérents :\n$dialogueContext\n\n"
}
