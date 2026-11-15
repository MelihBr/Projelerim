package com.melih.altyazi

/** Makine çevirisinin birebir çevirip kaçırdığı sokak argosu. */
object Slang {
    private val list: List<Triple<Regex, String, String>> = listOf(
        t("\\bthe heat\\b|\\bheat'?s (on|all over)", "the heat", "polis"),
        t("\\bfive-?o\\b|\\b5-?0\\b", "five-o", "polis"),
        t("\\bpigs\\b|\\bthe feds\\b", "pigs / the feds", "polis (aşağılayıcı) / federal ajanlar"),
        t("\\bsnitch(es|ing)?\\b|\\brat(ted)? (me |us )?out\\b", "snitch / rat out", "muhbir / ispiyonlamak"),
        t("\\bheist\\b", "heist", "büyük soygun"),
        t("\\bhomie\\b|\\bhomeboy\\b|\\bdawg\\b|\\bfam\\b", "homie / dawg / fam", "kanka"),
        t("\\bstrap\\b|\\bheater\\b|\\bpacking heat\\b", "strap / heater", "silah"),
        t("\\bsmoke (him|her|them|'em)\\b|\\bpop (him|her|them|'em)\\b|\\bwhack\\b", "smoke / pop / whack", "öldürmek, vurmak"),
        t("\\bthe hood\\b|\\bon the block\\b", "the hood / the block", "mahalle"),
        t("\\bwhip\\b", "whip", "araba"),
        t("\\bgetaway\\b", "getaway", "kaçış (aracı)"),
        t("\\bthe plug\\b|\\bmy plug\\b", "plug", "tedarikçi (çoğu zaman uyuşturucu)"),
        t("\\blay low\\b", "lay low", "ortadan kaybolmak, sessiz kalmak"),
        t("\\bdo time\\b|\\bdoing time\\b|\\bthe pen\\b", "do time / the pen", "hapis yatmak / hapishane"),
        t("\\bparole officer\\b|\\bmy P\\.?O\\.?\\b", "PO / parole officer", "şartlı tahliye memuru"),
        t("\\bmy bad\\b", "my bad", "pardon, benim hatam"),
        t("\\bno cap\\b", "no cap", "yalan yok, cidden"),
        t("\\bgo(ing|ne)? south\\b|\\bwent south\\b", "go south", "işler kötüye gitmek"),
        t("\\bride or die\\b", "ride or die", "ölümüne sadık")
    )

    private fun t(re: String, term: String, meaning: String) =
        Triple(Regex(re, RegexOption.IGNORE_CASE), term, meaning)

    fun notes(en: String): List<String> =
        list.filter { it.first.containsMatchIn(en) }.take(3).map { "${it.second} = ${it.third}" }
}
