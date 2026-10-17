package com.bizzle.minicpm.core

/** Which on-device runtime runs a model. */
enum class EngineKind { LLAMA_CPP, LITERT_LM }

/** Preferred compute unit. NPU = Tensor G5 TPU via LiteRT-LM. */
enum class Compute { CPU, GPU, NPU }

enum class FileRole { MAIN, MMPROJ, MMPROJ_DETAIL }

data class ModelFile(
    val repo: String,
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    val role: FileRole,
) {
    /** Direct Hugging Face download URL (public, ungated, no account needed). */
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$name?download=true"
    /** Local file name; repo-prefixed so two repos can't collide. */
    val localName: String get() = repo.replace('/', '_') + "__" + name
}

data class ModelSpec(
    val id: String,
    val name: String,
    val family: String,
    val engine: EngineKind,
    val quant: String,
    val files: List<ModelFile>,
    val license: String,
    val licenseUrl: String,
    val vision: Boolean,
    val thinking: Boolean,
    val compute: Compute,
    val minRamGb: Int,
    val blurb: String,
    val badge: String = "",
    val isDefault: Boolean = false,
) {
    val main: ModelFile get() = files.first { it.role == FileRole.MAIN }
    val mmproj: ModelFile? get() = files.firstOrNull { it.role == FileRole.MMPROJ }
    val detailMmproj: ModelFile? get() = files.firstOrNull { it.role == FileRole.MMPROJ_DETAIL }
    /** Bytes for the files you need to run it (detail projector is optional). */
    val requiredBytes: Long get() = files.filter { it.role != FileRole.MMPROJ_DETAIL }.sumOf { it.sizeBytes }
}

/**
 * Curated catalog. Every file name, size and SHA-256 below was checked against the Hugging Face API
 * on 2026-10-17. All repos are public and ungated.
 */
object ModelCatalog {
    private const val MCPM = "openbmb/MiniCPM-V-4.7-1B-gguf"
    private val mcpmMmproj = ModelFile(MCPM, "mmproj-model-f16.gguf", 1_110_117_984L,
        "fb9cc047a3cdad5317fd8433a4aeda2d22f00f02ca4bd93b34ad84165b25a900", FileRole.MMPROJ)
    private val mcpmMmproj4x = ModelFile(MCPM, "mmproj-model-f16-4x.gguf", 901_033_888L,
        "1e0ab439877e6896ded59ff24cdd242fdffb236394f6fc4a5b019cbd60bb5f4a", FileRole.MMPROJ_DETAIL)
    private const val APACHE = "Apache-2.0"

    val all: List<ModelSpec> = listOf(
        ModelSpec(
            id = "minicpmv47-1b-q8", name = "MiniCPM-V 4.7 1B · Q8_0", family = "MiniCPM-V 4.7",
            engine = EngineKind.LLAMA_CPP, quant = "Q8_0 + f16 vision",
            files = listOf(
                ModelFile(MCPM, "MiniCPM-V-4_7-1B-Q8_0.gguf", 1_081_517_920L,
                    "2b83d8dfcd664c4dd9b9cfbe8dbd160782f2c6c362ca773758edc2dbc31420d7", FileRole.MAIN),
                mcpmMmproj, mcpmMmproj4x),
            license = APACHE, licenseUrl = "https://huggingface.co/$MCPM",
            vision = true, thinking = true, compute = Compute.CPU, minRamGb = 4,
            blurb = "Default. Biggest MiniCPM-V 4.7 that runs on a phone, at its best-quality quant. Chat, photos, screenshots, OCR.",
            badge = "DEFAULT · BEST QUALITY", isDefault = true),
        ModelSpec(
            id = "minicpmv47-1b-q4km", name = "MiniCPM-V 4.7 1B · Q4_K_M (fast)", family = "MiniCPM-V 4.7",
            engine = EngineKind.LLAMA_CPP, quant = "Q4_K_M + f16 vision",
            files = listOf(
                ModelFile(MCPM, "MiniCPM-V-4_7-1B-Q4_K_M.gguf", 672_003_680L,
                    "588c6c94c82f756b86dba35c42ed9ba9ec187d65824eb52f039c347df563e63d", FileRole.MAIN),
                mcpmMmproj, mcpmMmproj4x),
            license = APACHE, licenseUrl = "https://huggingface.co/$MCPM",
            vision = true, thinking = true, compute = Compute.CPU, minRamGb = 3,
            blurb = "Same model, smaller 4-bit weights. Faster and lighter, slightly less accurate.",
            badge = "FAST"),
        ModelSpec(
            id = "gemma4-e2b-g5-npu", name = "Gemma 4 E2B · Tensor G5 NPU", family = "Gemma 4",
            engine = EngineKind.LITERT_LM, quant = "LiteRT-LM (precompiled for Tensor G5)",
            files = listOf(ModelFile("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
                3_113_545_589L, "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc", FileRole.MAIN)),
            license = APACHE, licenseUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            vision = true, thinking = false, compute = Compute.NPU, minRamGb = 4,
            blurb = "Runs on the Pixel 10's AI chip (TPU) through Google LiteRT-LM, leaving the CPU free. Experimental: only works on Tensor G5.",
            badge = "AI CHIP · EXPERIMENTAL"),
        ModelSpec(
            id = "gemma4-e2b-litert", name = "Gemma 4 E2B · LiteRT CPU/GPU", family = "Gemma 4",
            engine = EngineKind.LITERT_LM, quant = "LiteRT-LM int4",
            files = listOf(ModelFile("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm",
                2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", FileRole.MAIN)),
            license = APACHE, licenseUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            vision = true, thinking = false, compute = Compute.CPU, minRamGb = 4,
            blurb = "Google's Gemma 4 E2B on the LiteRT-LM engine (CPU, GPU optional). Works on any phone."),
        ModelSpec(
            id = "gemma4-e4b-litert", name = "Gemma 4 E4B · LiteRT (big brain)", family = "Gemma 4",
            engine = EngineKind.LITERT_LM, quant = "LiteRT-LM int4",
            files = listOf(ModelFile("litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm",
                3_659_530_240L, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", FileRole.MAIN)),
            license = APACHE, licenseUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            vision = true, thinking = false, compute = Compute.CPU, minRamGb = 6,
            blurb = "Bigger, smarter Gemma 4 for heavier jobs. Slower (est. 5-8 tok/s).", badge = "HEAVY"),
        ModelSpec(
            id = "qwen35-2b-q4km", name = "Qwen3.5 2B · Q4_K_M", family = "Qwen3.5",
            engine = EngineKind.LLAMA_CPP, quant = "Q4_K_M + f16 vision",
            files = listOf(
                ModelFile("unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_K_M.gguf", 1_280_835_840L,
                    "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", FileRole.MAIN),
                ModelFile("unsloth/Qwen3.5-2B-GGUF", "mmproj-F16.gguf", 668_227_264L,
                    "7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7", FileRole.MMPROJ)),
            license = APACHE, licenseUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF",
            vision = true, thinking = true, compute = Compute.CPU, minRamGb = 4,
            blurb = "Smarter text and reasoning than the 1B, with vision. About 10-14 tok/s measured on Pixel 10."),
        ModelSpec(
            id = "lfm25-1.2b-q4km", name = "LFM2.5 1.2B · Q4_K_M (speed)", family = "LFM2.5",
            engine = EngineKind.LLAMA_CPP, quant = "Q4_K_M",
            files = listOf(ModelFile("LiquidAI/LFM2.5-1.2B-Instruct-GGUF", "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
                730_895_168L, "b1b3de114215d9507409a662a501a631095a479a419584e8a2ded6304b19b4f5", FileRole.MAIN)),
            license = "LFM Open License v1.0 (free for personal use)",
            licenseUrl = "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/blob/main/LICENSE",
            vision = false, thinking = false, compute = Compute.CPU, minRamGb = 2,
            blurb = "Fastest text helper (about 30 tok/s measured on Pixel 10). Text only.", badge = "FASTEST"),
        ModelSpec(
            id = "gemma4-e4b-q4-gguf", name = "Gemma 4 E4B · Q4_0 GGUF", family = "Gemma 4",
            engine = EngineKind.LLAMA_CPP, quant = "Q4_0 + Q8_0 vision",
            files = listOf(
                ModelFile("ggml-org/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_0.gguf", 4_590_807_392L,
                    "a555b900214b477d8880e7832e0b8925e139b0159640036b09fe472b6f2097f2", FileRole.MAIN),
                ModelFile("ggml-org/gemma-4-E4B-it-GGUF", "mmproj-gemma-4-E4B-it-Q8_0.gguf", 559_874_816L,
                    "197f49a93027f9843772bd24a6a9e0be2a32a788de5a3def330e9c585d86edd1", FileRole.MMPROJ)),
            license = APACHE, licenseUrl = "https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF",
            vision = true, thinking = false, compute = Compute.CPU, minRamGb = 7,
            blurb = "Big brain on llama.cpp. Slow (about 7 tok/s measured) but the smartest option here.", badge = "HEAVY"),
    )

    val default: ModelSpec get() = all.first { it.isDefault }
    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }

    /** Distinct files across the catalog (shared projectors are only downloaded once). */
    fun allFiles(): List<ModelFile> = all.flatMap { it.files }.distinctBy { it.localName }
}

object Bytes {
    fun human(b: Long): String {
        if (b < 1024) return "$b B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = b.toDouble() / 1024; var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.2f %s".format(v, units[i])
    }
}
