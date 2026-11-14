package com.melih.altyazi

/** Makine çevirisinin birebir çevirip kaçırdığı sokak argosu. */
object Slang {
    private val list: List<Triple<Regex, String, String>> = listOf(
        t("\\bthe heat\\b|\\bheat'?s (on|all over)", "the heat", "polis"),
        t("\\bfive-?o\\b|\\b5-?0\\b", "five-o", "polis"),
        t("\\bcops?\\b|\\bpigs\\b|\\bfeds?\\b", "cops / pigs / feds", "polis / federal ajanlar"),
        t("\\bsnitch(es|ing)?\\b|\\brat(ted)? (me |us )?out\\b", "snitch / rat out", "muhbir / ispiyonlamak"),
        t("\\bscore\\b", "score", "vurgun, iş (soygun)"),
        t("\\bheist\\b", "heist", "büyük soygun"),
        t("\\bstash\\b", "stash", "zula"),
        t("\\bdough\\b|\\bbread\\b|\\bbucks\\b|\\bgrand\\b|\\bstacks?\\b", "dough / bread / grand", "para (grand = bin dolar)"),
        t("\\bhomie\\b|\\bhomeboy\\b|\\bdawg\\b|\\bfam\\b", "homie / dawg / fam", "kanka"),
        t("\\bshorty\\b|\\bshawty\\b", "shorty", "sevgili / kız"),
        t("\\bbae\\b|\\bboo\\b", "bae / boo", "sevgili"),
        t("\\bain'?t\\b", "ain't", "değil / yok (sokak ağzı)"),
        t("\\bgonna\\b|\\bwanna\\b|\\bgotta\\b", "gonna / wanna / gotta", "-ecek / istemek / zorunda"),
        t("\\bstrap\\b|\\bheater\\b|\\bpacking heat\\b", "strap / heater", "silah"),
        t("\\bstrapped\\b|\\bpacking\\b", "strapped / packing", "silahlı"),
        t("\\bsmoke (him|her|them|'em)\\b|\\bpop (him|her|them|'em)\\b|\\bwhack\\b", "smoke / pop / whack", "öldürmek, vurmak"),
        t("\\bbeef\\b", "beef", "husumet, kavga"),
        t("\\bthe hood\\b|\\bon the block\\b", "the hood / the block", "mahalle"),
        t("\\bcrib\\b", "crib", "ev"),
        t("\\bwhip\\b", "whip", "araba"),
        t("\\bgetaway\\b", "getaway", "kaçış (aracı)"),
        t("\\bfence\\b", "fence", "çalıntı mal alıcısı"),
        t("\\bthe plug\\b|\\bmy plug\\b", "plug", "tedarikçi (çoğu zaman uyuşturucu)"),
        t("\\bdope\\b|\\bblow\\b|\\bcoke\\b|\\bweed\\b", "dope / blow / coke", "uyuşturucu"),
        t("\\blay low\\b", "lay low", "ortadan kaybolmak, sessiz kalmak"),
        t("\\bdo time\\b|\\bdoing time\\b|\\bthe pen\\b", "do time / the pen", "hapis yatmak / hapishane"),
        t("\\bparole officer\\b|\\bmy P\\.?O\\.?\\b", "PO / parole officer", "şartlı tahliye memuru"),
        t("\\bhustl(e|ing|er)\\b", "hustle", "üçkağıt / para kazanma çabası"),
        t("\\bmy bad\\b", "my bad", "pardon, benim hatam"),
        t("\\bno cap\\b", "no cap", "yalan yok, cidden"),
        t("\\blit\\b", "lit", "harika, coşkulu"),
        t("\\bsus\\b", "sus", "şüpheli"),
        t("\\bgo(ing|ne)? south\\b|\\bwent south\\b", "go south", "işler kötüye gitmek"),
        t("\\bride or die\\b", "ride or die", "ölümüne sadık")
    )

    private fun t(re: String, term: String, meaning: String) =
        Triple(Regex(re, RegexOption.IGNORE_CASE), term, meaning)

    fun notes(en: String): List<String> =
        list.filter { it.first.containsMatchIn(en) }.take(3).map { "${it.second} = ${it.third}" }
}
