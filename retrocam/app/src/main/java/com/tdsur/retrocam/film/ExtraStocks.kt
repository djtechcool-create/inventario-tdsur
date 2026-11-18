package com.tdsur.retrocam.film

/**
 * Más rollos, descritos por sus rasgos en lugar de curvas escritas a mano.
 * [stock] convierte esos rasgos en curvas por canal.
 * Kotlin puro (sin Android).
 */
internal object ExtraStocks {
    private val C = LookCategory.COLOR
    private val CINE = LookCategory.CINE
    private val SLIDE = LookCategory.SLIDE
    private val BW = LookCategory.BW
    private val INSTANT = LookCategory.INSTANT
    private val VINTAGE = LookCategory.VINTAGE
    private val EXP = LookCategory.EXPERIMENTAL
    private val SCAN = FrameStyle.SCAN
    private val NONE = FrameStyle.NONE
    private val POLA = FrameStyle.POLAROID

    private fun rgb(r: Float, g: Float, b: Float) = Rgb(r, g, b)

    /**
     * @param contrast -1 = muy suave, 0 = normal, +1 = muy duro
     * @param fade negros levantados; @param roll blancos apagados
     * @param shadow, mid, high color que el rollo da a sombras, medios y luces
     */
    private fun stock(
        id: String, name: String, description: String, swatch: Long, category: LookCategory,
        contrast: Float = 0f, fade: Float = 0.05f, roll: Float = 0.04f,
        shadow: Rgb = Rgb.ZERO, mid: Rgb = Rgb.ZERO, high: Rgb = Rgb.ZERO,
        saturation: Float = 1f, exposure: Float = 0f, mix: FloatArray = FilmLook.IDENTITY,
        mono: Float = 0f, monoWeights: Rgb = Rgb.LUMA,
        grain: Float = 0.045f, grainSize: Float = 1f, grainColor: Float = 0.35f,
        halation: Float = 0.2f, bloom: Float = 0.1f, vignette: Float = 0.18f, softness: Float = 0.35f,
        aberration: Float = 0.0015f, leak: Float = 0f, flash: Float = 0f, dust: Float = 0f,
        satRollHi: Float = 0.45f, satRollLo: Float = 0.2f, flatten: Float = 0.3f,
        frame: FrameStyle = SCAN, date: Boolean = false,
    ): FilmLook {
        fun curve(sh: Float, md: Float, hi: Float): ToneCurve {
            val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            val base = floatArrayOf(0f, 0.25f - 0.07f * contrast, 0.5f, 0.75f + 0.07f * contrast, 1f)
            val ys = FloatArray(5) { fade + (1f - fade - roll) * base[it] }
            ys[0] += sh
            ys[1] += sh * 0.6f + md * 0.4f
            ys[2] += md
            ys[3] += hi * 0.6f + md * 0.4f
            ys[4] += hi
            for (i in ys.indices) {
                ys[i] = ys[i].coerceIn(0f, 1f)
                if (i > 0 && ys[i] < ys[i - 1] + 0.01f) ys[i] = (ys[i - 1] + 0.01f).coerceAtMost(1f)
            }
            val p = FloatArray(10)
            for (i in 0 until 5) {
                p[i * 2] = xs[i]
                p[i * 2 + 1] = ys[i]
            }
            return ToneCurve(*p)
        }
        return FilmLook(
            id = id, name = name, description = description, swatch = swatch, category = category,
            exposure = exposure, mix = mix, mono = mono, monoWeights = monoWeights,
            curveR = curve(shadow.r, mid.r, high.r),
            curveG = curve(shadow.g, mid.g, high.g),
            curveB = curve(shadow.b, mid.b, high.b),
            saturation = saturation, satRollHi = satRollHi, satRollLo = satRollLo, flatten = flatten,
            softness = softness, aberration = aberration, bloom = bloom, halation = halation,
            grain = grain, grainSize = grainSize, grainColor = if (mono > 0f) 0f else grainColor,
            vignette = vignette, flash = flash, leak = leak, dust = dust,
            defaultFrame = frame, defaultDate = date,
        )
    }

    private val WARM_HI = rgb(0.025f, 0.005f, -0.045f)

    val all: List<FilmLook> = listOf(
        // ------------------------------------------------------------ Color negativo
        stock("gold100", "Gold 100", "Kodak · cálido y de grano fino", 0xFFE7B04E, C,
            contrast = 0.1f, mid = rgb(0.01f, 0f, -0.02f), high = rgb(0.02f, 0f, -0.05f), grain = 0.03f),
        stock("royalgold400", "Royal Gold 400", "Kodak · dorado y saturado", 0xFFD9962B, C,
            contrast = 0.2f, high = rgb(0.03f, 0.01f, -0.05f), saturation = 1.1f, grain = 0.05f, halation = 0.3f),
        stock("kodacolor200", "Kodacolor 200", "Kodak · años 70, cálido y desteñido", 0xFFC98B5B, C,
            contrast = -0.2f, fade = 0.1f, roll = 0.07f, shadow = rgb(0.02f, 0f, -0.02f), high = rgb(0.04f, 0.01f, -0.06f),
            saturation = 0.8f, grain = 0.06f, vignette = 0.3f, softness = 0.5f),
        stock("max800", "Max 800", "Kodak · fiestas con flash", 0xFFE0703A, C,
            contrast = 0.3f, high = rgb(0.02f, 0f, -0.03f), saturation = 1.1f, grain = 0.08f, grainSize = 1.2f, flash = 0.25f, vignette = 0.3f),
        stock("portra160nc", "Portra 160NC", "Kodak · neutro y muy suave", 0xFFE3CDB9, C,
            contrast = -0.25f, fade = 0.06f, saturation = 0.8f, satRollHi = 0.6f, grain = 0.03f),
        stock("portra400vc", "Portra 400VC", "Kodak · retrato con colores vivos", 0xFFE79E80, C,
            contrast = 0.1f, high = rgb(0.02f, 0.01f, -0.02f), saturation = 1.1f, grain = 0.045f),
        stock("proimage100", "Pro Image 100", "Kodak · cálido tropical", 0xFFE6A15C, C,
            contrast = 0.1f, mid = rgb(0.015f, 0f, -0.02f), saturation = 1.05f, grain = 0.035f),
        stock("vericolor", "Vericolor III", "Kodak · estudio de los 80", 0xFFC9B6A3, C,
            contrast = -0.15f, fade = 0.07f, shadow = rgb(0f, 0.01f, 0.02f), high = rgb(0.02f, 0.01f, -0.01f), saturation = 0.85f),
        stock("fuji100", "Fujicolor 100", "Fujifilm · nítido con verdes frescos", 0xFF6FA36B, C,
            contrast = 0.1f, shadow = rgb(-0.01f, 0.02f, 0.01f), high = rgb(0.01f, 0f, 0.01f), grain = 0.035f),
        stock("superia200", "Superia 200", "Fujifilm · cotidiano y fresco", 0xFF79A88D, C,
            shadow = rgb(-0.01f, 0.025f, 0.02f), saturation = 0.95f, grain = 0.045f),
        stock("superiaxtra400", "Superia X-TRA 400", "Fujifilm · sombras verde azulado", 0xFF3E8F86, C,
            contrast = 0.15f, shadow = rgb(-0.02f, 0.03f, 0.03f), high = rgb(0.02f, -0.005f, 0.02f), saturation = 1.05f, grain = 0.055f),
        stock("superia800", "Superia 800", "Fujifilm · noche con grano", 0xFF4F7A86, C,
            contrast = 0.1f, shadow = rgb(-0.01f, 0.02f, 0.03f), saturation = 0.95f, grain = 0.08f, grainSize = 1.3f, halation = 0.3f),
        stock("natura1600", "Natura 1600", "Fujifilm · luz ambiente sin flash", 0xFFB08F6A, C,
            contrast = -0.1f, fade = 0.07f, mid = rgb(0.01f, 0.01f, -0.01f), saturation = 0.9f, grain = 0.09f, grainSize = 1.4f),
        stock("reala100", "Reala 100", "Fujifilm · colores fieles y limpios", 0xFF8DB0C2, C,
            high = rgb(0f, 0.005f, 0f), grain = 0.025f),
        stock("pro160ns", "Pro 160NS", "Fujifilm · pastel frío para bodas", 0xFFBFD8D2, C,
            contrast = -0.2f, exposure = 0.2f, fade = 0.06f, shadow = rgb(-0.01f, 0.02f, 0.02f), high = rgb(0f, 0.01f, 0.01f),
            saturation = 0.82f, grain = 0.035f, bloom = 0.15f),
        stock("vista200", "Vista 200", "Agfa · rojos intensos y cálido", 0xFFC93A2E, C,
            contrast = 0.15f, high = rgb(0.03f, 0f, -0.03f), saturation = 1.1f, grain = 0.05f,
            mix = floatArrayOf(1.06f, -0.04f, -0.02f, -0.02f, 1.03f, -0.01f, -0.01f, -0.02f, 1.03f)),
        stock("vista400", "Vista 400", "Agfa · rojos y verdes saturados", 0xFFB5463A, C,
            contrast = 0.2f, saturation = 1.15f, grain = 0.06f),
        stock("agfaultra100", "Ultra 100", "Agfa · ultra saturado", 0xFFE0402A, C,
            contrast = 0.3f, saturation = 1.3f, grain = 0.03f),
        stock("optima200", "Optima 200", "Agfa · neutro y elegante", 0xFFA9A49A, C,
            shadow = rgb(0f, 0f, 0.01f), saturation = 0.95f, grain = 0.04f),
        stock("centuria400", "Centuria 400", "Konica · tonos verdosos japoneses", 0xFF8EA37A, C,
            contrast = 0.05f, shadow = rgb(-0.01f, 0.02f, 0f), mid = rgb(0f, 0.01f, 0f), saturation = 0.95f, grain = 0.055f),
        stock("impresa50", "Impresa 50", "Konica · grano finísimo y contraste", 0xFFD07A55, C,
            contrast = 0.3f, saturation = 1.05f, grain = 0.02f),
        stock("lomo100", "Lomo 100", "Lomography · vivo y soleado", 0xFFF2A93B, C,
            contrast = 0.25f, high = rgb(0.02f, 0.01f, -0.02f), saturation = 1.2f, vignette = 0.4f, grain = 0.04f, frame = NONE),
        stock("lomo400", "Lomo 400", "Lomography · contrastado y cálido", 0xFFDB6B3A, C,
            contrast = 0.3f, high = rgb(0.03f, 0.01f, -0.03f), saturation = 1.15f, vignette = 0.45f, grain = 0.06f, frame = NONE),
        stock("phoenix200", "Phoenix 200", "Harman · halación roja y colores raros", 0xFFD2523C, C,
            contrast = 0.35f, fade = 0.04f, shadow = rgb(0.02f, -0.01f, 0.02f), high = rgb(0.04f, 0.01f, -0.03f),
            saturation = 1.05f, halation = 1.1f, grain = 0.08f, grainSize = 1.3f),

        // ------------------------------------------------------------ Cine
        stock("vision250d", "Vision3 250D", "Kodak cine · día, suave y natural", 0xFF9FB3B8, CINE,
            contrast = -0.15f, fade = 0.06f, saturation = 0.92f, halation = 0.3f, grain = 0.04f),
        stock("vision50d", "Vision3 50D", "Kodak cine · día, finísimo", 0xFFB7C7CB, CINE,
            contrast = -0.1f, saturation = 0.95f, grain = 0.02f),
        stock("vision200t", "Vision3 200T", "Kodak cine · luz cálida de tungsteno", 0xFF5D7F8E, CINE,
            contrast = -0.1f, shadow = rgb(-0.01f, 0.01f, 0.03f), high = rgb(0.02f, 0.01f, -0.01f), saturation = 0.9f, halation = 0.45f),
        stock("eterna250d", "Eterna 250D", "Fujifilm cine · apagado y verdoso", 0xFF83978A, CINE,
            contrast = -0.25f, fade = 0.07f, shadow = rgb(-0.01f, 0.02f, 0.01f), saturation = 0.78f, grain = 0.04f),
        stock("eternavivid500", "Eterna Vivid 500", "Fujifilm cine · noche saturada", 0xFF3D5F79, CINE,
            contrast = 0.05f, shadow = rgb(-0.01f, 0.01f, 0.03f), saturation = 1.05f, halation = 0.5f, grain = 0.06f),
        stock("cinestill400d", "CineStill 400D", "Cine de día con halación", 0xFF6F9FC0, CINE,
            contrast = 0.05f, halation = 0.7f, grain = 0.045f),

        // ------------------------------------------------------------ Diapositiva
        stock("kodachrome64", "Kodachrome 64", "Kodak · el clásico, rojos y azules profundos", 0xFFB8332B, SLIDE,
            contrast = 0.35f, fade = 0.02f, shadow = rgb(0f, 0f, 0.01f), high = rgb(0.02f, 0.01f, -0.02f), saturation = 1.15f,
            mix = floatArrayOf(1.08f, -0.06f, -0.02f, -0.03f, 1.04f, -0.01f, -0.02f, -0.04f, 1.06f), grain = 0.03f, frame = NONE),
        stock("kodachrome25", "Kodachrome 25", "Kodak · finísimo y cálido", 0xFFC24A33, SLIDE,
            contrast = 0.3f, high = rgb(0.025f, 0.01f, -0.03f), saturation = 1.1f, grain = 0.015f, frame = NONE),
        stock("ektachrome64", "Ektachrome 64", "Kodak · años 70, azulado", 0xFF5A86B5, SLIDE,
            contrast = 0.25f, fade = 0.04f, shadow = rgb(0f, 0f, 0.03f), high = rgb(0f, 0.01f, 0.02f), saturation = 1.05f, frame = NONE),
        stock("e100vs", "E100VS", "Kodak · supersaturado", 0xFFD23A6E, SLIDE,
            contrast = 0.35f, saturation = 1.35f, grain = 0.02f, frame = NONE),
        stock("elitechrome", "Elite Chrome 100", "Kodak · vivo y cálido", 0xFFE0703E, SLIDE,
            contrast = 0.3f, high = rgb(0.02f, 0f, -0.02f), saturation = 1.15f, grain = 0.02f, frame = NONE),
        stock("provia100f", "Provia 100F", "Fujifilm · neutro y nítido", 0xFF4F8FBF, SLIDE,
            contrast = 0.3f, saturation = 1.08f, grain = 0.02f, frame = NONE),
        stock("astia100f", "Astia 100F", "Fujifilm · suave para piel", 0xFFE0B8A4, SLIDE,
            contrast = 0.1f, high = rgb(0.01f, 0.005f, 0f), saturation = 0.98f, grain = 0.02f, frame = NONE),
        stock("velvia100", "Velvia 100", "Fujifilm · saturado sin exagerar", 0xFF3B9A55, SLIDE,
            contrast = 0.35f, saturation = 1.25f, grain = 0.02f, frame = NONE),
        stock("precisa100", "Precisa CT100", "Agfa · azules fríos", 0xFF3E6FB0, SLIDE,
            contrast = 0.3f, shadow = rgb(0f, 0f, 0.03f), high = rgb(0f, 0.01f, 0.02f), saturation = 1.1f, frame = NONE),

        // ------------------------------------------------------------ Blanco y negro
        stock("trix1600", "Tri-X 1600", "Kodak forzado · negros profundos y grano", 0xFF1E1E1E, BW,
            mono = 1f, contrast = 0.6f, fade = 0.02f, grain = 0.16f, grainSize = 1.6f, halation = 0f, frame = NONE),
        stock("plusx125", "Plus-X 125", "Kodak · clásico de los 60", 0xFF6E6E6E, BW,
            mono = 1f, contrast = 0.2f, fade = 0.04f, grain = 0.05f, halation = 0f, frame = NONE),
        stock("tmaxp3200", "T-Max P3200", "Kodak · noche muy granulada", 0xFF333333, BW,
            mono = 1f, contrast = 0.1f, fade = 0.07f, grain = 0.17f, grainSize = 1.7f, halation = 0f, frame = NONE),
        stock("doublex", "Double-X 5222", "Kodak cine · contraste de película", 0xFF2B2B2B, BW,
            mono = 1f, contrast = 0.4f, grain = 0.09f, grainSize = 1.3f, halation = 0f, frame = NONE),
        stock("fp4", "FP4 Plus 125", "Ilford · tonos medios ricos", 0xFF7A7A7A, BW,
            mono = 1f, contrast = 0.1f, grain = 0.04f, halation = 0f, frame = NONE),
        stock("panf50", "Pan F Plus 50", "Ilford · contraste alto y sin grano", 0xFF4A4A4A, BW,
            mono = 1f, contrast = 0.5f, grain = 0.015f, halation = 0f, frame = NONE),
        stock("delta100", "Delta 100", "Ilford · limpio y moderno", 0xFF8C8C8C, BW,
            mono = 1f, contrast = 0.15f, grain = 0.025f, halation = 0f, frame = NONE),
        stock("xp2", "XP2 Super 400", "Ilford · suave con base cálida", 0xFF9A948C, BW,
            mono = 1f, contrast = -0.15f, fade = 0.06f, high = rgb(0.015f, 0.01f, 0f), grain = 0.04f, halation = 0f, frame = NONE),
        stock("sfx200", "SFX 200", "Ilford · casi infrarrojo, cielos negros", 0xFF1A1A1A, BW,
            mono = 1f, monoWeights = rgb(0.8f, 0.25f, -0.05f), contrast = 0.4f, grain = 0.07f, halation = 0f, frame = NONE),
        stock("kentmere400", "Kentmere 400", "Harman · económico y granulado", 0xFF5C5C5C, BW,
            mono = 1f, contrast = 0.25f, grain = 0.09f, halation = 0f, frame = NONE),
        stock("fomapan100", "Fomapan 100", "Foma · vintage checo", 0xFF847D74, BW,
            mono = 1f, contrast = 0.2f, fade = 0.05f, high = rgb(0.01f, 0.005f, 0f), grain = 0.05f, halation = 0f, frame = NONE),
        stock("fomapan400", "Fomapan 400", "Foma · grano grueso", 0xFF555555, BW,
            mono = 1f, contrast = 0.25f, grain = 0.11f, grainSize = 1.4f, halation = 0f, frame = NONE),
        stock("rpx400", "RPX 400", "Rollei · contraste fuerte", 0xFF3D3D3D, BW,
            mono = 1f, contrast = 0.35f, grain = 0.09f, halation = 0f, frame = NONE),
        stock("retro80s", "Retro 80S", "Rollei · cielos oscuros y nítido", 0xFF2F2F2F, BW,
            mono = 1f, monoWeights = rgb(0.55f, 0.4f, 0.05f), contrast = 0.35f, grain = 0.04f, halation = 0f, frame = NONE),
        stock("scala200", "Scala 200x", "Agfa · diapositiva B&N, negros profundos", 0xFF111111, BW,
            mono = 1f, contrast = 0.45f, fade = 0f, grain = 0.05f, halation = 0f, frame = NONE),
        stock("bwxx", "CineStill BwXX", "Cine en blanco y negro", 0xFF404040, BW,
            mono = 1f, contrast = 0.3f, grain = 0.08f, halation = 0f, frame = NONE),

        // ------------------------------------------------------------ Instantánea
        stock("sx70", "Polaroid SX-70", "Instantánea de los 70, cálida y lechosa", 0xFFC9A98A, INSTANT,
            exposure = 0.1f, contrast = -0.3f, fade = 0.12f, roll = 0.1f, shadow = rgb(0.03f, 0f, 0.04f), high = rgb(0.04f, 0.03f, -0.02f),
            saturation = 0.75f, softness = 1.0f, vignette = 0.35f, bloom = 0.2f, frame = POLA),
        stock("spectra", "Polaroid Spectra", "Instantánea panorámica de los 80", 0xFFB9C4B8, INSTANT,
            contrast = -0.1f, fade = 0.09f, shadow = rgb(0f, 0.02f, 0.03f), high = rgb(0.03f, 0.02f, -0.02f),
            saturation = 0.85f, softness = 0.8f, frame = POLA),
        stock("polaroid667", "Polaroid 667 B&N", "Instantánea en blanco y negro", 0xFF6A6A6A, INSTANT,
            mono = 1f, contrast = 0.1f, fade = 0.06f, softness = 0.7f, grain = 0.03f, frame = POLA),
        stock("instaxwide", "Instax Wide", "Fujifilm · instantánea clara y fresca", 0xFFD7E6EF, INSTANT,
            exposure = 0.2f, contrast = -0.05f, high = rgb(0f, 0.01f, 0.02f), saturation = 0.92f, softness = 0.6f, frame = POLA),
        stock("fp100c", "FP-100C", "Fujifilm instantánea de despegar, cian suave", 0xFF9CC7C9, INSTANT,
            contrast = -0.05f, fade = 0.07f, shadow = rgb(-0.02f, 0.02f, 0.04f), saturation = 0.9f, softness = 0.6f),

        // ------------------------------------------------------------ Desechables y vintage
        stock("quicksnap400", "QuickSnap 400", "Fujifilm desechable · flash y fecha", 0xFF3FA06A, VINTAGE,
            contrast = 0.25f, shadow = rgb(-0.01f, 0.02f, 0.02f), saturation = 1.05f, flash = 0.35f, vignette = 0.5f,
            softness = 0.8f, aberration = 0.003f, leak = 0.3f, grain = 0.065f, frame = NONE, date = true),
        stock("instamatic", "Instamatic 126", "Kodak · fotos familiares de los 70", 0xFFC79A64, VINTAGE,
            contrast = -0.1f, fade = 0.1f, high = rgb(0.04f, 0.02f, -0.05f), saturation = 0.85f, vignette = 0.45f, softness = 1.0f,
            grain = 0.06f, frame = NONE),
        stock("daguerrotipo", "Daguerrotipo", "1850 · plata fría y bordes oscuros", 0xFF6F7680, VINTAGE,
            mono = 1f, contrast = 0.2f, fade = 0.1f, shadow = rgb(0f, 0.01f, 0.02f), high = rgb(0f, 0.01f, 0.02f),
            vignette = 0.7f, dust = 0.9f, softness = 1.8f, grain = 0.06f, grainSize = 1.6f, aberration = 0f, frame = NONE),
        stock("cianotipo", "Cianotipo", "Copia azul de 1900", 0xFF1F4E8C, VINTAGE,
            mono = 1f, contrast = 0.15f, fade = 0.04f, shadow = rgb(-0.02f, 0.06f, 0.25f), mid = rgb(-0.08f, 0f, 0.12f),
            high = rgb(-0.03f, 0f, 0.04f), vignette = 0.4f, dust = 0.4f, softness = 1.2f, grain = 0.05f, aberration = 0f, frame = NONE),

        // ------------------------------------------------------------ Experimental
        stock("lomopurple", "LomoChrome Purple", "Lomography · verdes que se vuelven morados", 0xFF8B4FB3, EXP,
            contrast = 0.15f, saturation = 1.15f, grain = 0.06f,
            mix = floatArrayOf(0.6f, 0.5f, -0.1f, 0.1f, 0.25f, 0.65f, 0f, 0.7f, 0.3f)),
        stock("metropolis", "LomoChrome Metropolis", "Lomography · desaturado y duro", 0xFF6B6A63, EXP,
            contrast = 0.4f, saturation = 0.45f, shadow = rgb(0f, 0.01f, 0.02f), high = rgb(0.02f, 0.01f, -0.01f), grain = 0.06f),
        stock("turquoise", "LomoChrome Turquoise", "Lomography · cielos naranjas y piel azul", 0xFF2FB3A8, EXP,
            contrast = 0.15f, saturation = 1.1f, grain = 0.06f,
            mix = floatArrayOf(0.3f, 0f, 0.7f, 0.2f, 0.6f, 0.2f, 0.7f, 0.1f, 0.2f)),
        stock("redscale", "Redscale", "Rollo al revés · todo rojo y naranja", 0xFFC2410C, EXP,
            contrast = 0.2f, grain = 0.07f, vignette = 0.4f,
            mix = floatArrayOf(0.9f, 0.3f, 0f, 0.15f, 0.45f, 0f, 0.05f, 0.1f, 0f)),
        stock("aerochrome", "Aerochrome", "Kodak infrarrojo · vegetación roja", 0xFFE0365A, EXP,
            contrast = 0.2f, saturation = 1.2f, grain = 0.05f,
            mix = floatArrayOf(0.4f, 0.75f, -0.1f, 0.55f, 0.3f, 0.05f, 0.1f, 0.1f, 0.8f)),
        stock("crossprocess", "Proceso cruzado", "Diapositiva revelada como negativo", 0xFFB8D23A, EXP,
            contrast = 0.45f, shadow = rgb(0f, 0.02f, 0.08f), high = rgb(0.04f, 0.05f, -0.08f), saturation = 1.25f, grain = 0.05f),
        stock("bleachbypass", "Bleach bypass", "Plata retenida · cine desaturado", 0xFF5F5A52, EXP,
            contrast = 0.5f, fade = 0.02f, saturation = 0.45f, grain = 0.05f, halation = 0.1f),
    )
}
