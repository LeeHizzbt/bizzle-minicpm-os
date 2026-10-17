package com.bizzle.minicpm.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CatalogTest {
    @Test fun defaultIsMiniCpmQ8WithF16Projector() {
        val d = ModelCatalog.default
        assertEquals("minicpmv47-1b-q8", d.id)
        assertEquals("MiniCPM-V-4_7-1B-Q8_0.gguf", d.main.name)
        assertEquals("mmproj-model-f16.gguf", d.mmproj!!.name)
        assertTrue(d.vision)
        assertEquals(1_081_517_920L + 1_110_117_984L, d.requiredBytes)
    }
    @Test fun catalogIsConsistent() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(1, ModelCatalog.all.count { it.isDefault })
        ModelCatalog.all.forEach { m ->
            assertTrue(m.files.count { it.role == FileRole.MAIN } == 1)
            m.files.forEach { f ->
                assertTrue(f.sizeBytes > 1_000_000)
                assertTrue("${f.name} sha", f.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(f.url.startsWith("https://huggingface.co/${f.repo}/resolve/main/"))
            }
            if (m.engine == EngineKind.LITERT_LM) assertTrue(m.main.name.endsWith(".litertlm")) else assertTrue(m.main.name.endsWith(".gguf"))
        }
        // Q8 and Q4 share one projector file on disk
        assertEquals(ModelCatalog.byId("minicpmv47-1b-q8")!!.mmproj!!.localName, ModelCatalog.byId("minicpmv47-1b-q4km")!!.mmproj!!.localName)
        assertTrue(ModelCatalog.allFiles().size < ModelCatalog.all.sumOf { it.files.size })
    }
    @Test fun humanBytes() {
        assertEquals("1.01 GB", Bytes.human(1_081_517_920L))
        assertEquals("512 B", Bytes.human(512))
    }
}

class ThinkParserTest {
    @Test fun promptOpenedThink() {
        val s = ThinkParser.parse("let me think\n</think>\n\nThe answer is 4.", startsInThink = true)
        assertEquals("let me think", s.reasoning); assertEquals("The answer is 4.", s.answer); assertFalse(s.thinking)
    }
    @Test fun streamingInsideThink() {
        val s = ThinkParser.parse("still thinking", true)
        assertTrue(s.thinking); assertEquals("", s.answer)
    }
    @Test fun inlineTagsAndPartialTag() {
        val s = ThinkParser.parse("<think>hmm</think>Hello</thi", false)
        assertEquals("hmm", s.reasoning); assertEquals("Hello", s.answer)
    }
    @Test fun emptyThinkBlock() {
        assertEquals("Hi", ThinkParser.parse("<think>\n\n</think>\n\nHi", false).answer)
    }
}

class MarkdownTest {
    @Test fun splitsCode() {
        val segs = MarkdownLite.split("Here:\n```kotlin\nval x = 1\n```\nDone")
        assertEquals(3, segs.size)
        assertEquals(MarkdownLite.Seg.Code("kotlin", "val x = 1"), segs[1])
    }
    @Test fun unterminatedCodeWhileStreaming() {
        val segs = MarkdownLite.split("A\n```py\nprint(1)")
        assertEquals(MarkdownLite.Seg.Code("py", "print(1)"), segs.last())
    }
}

class OcrGuardTest {
    @Test fun flagsSerialLikeTokens() {
        val r = OcrGuard.riskyTokens("Serial number: SN-8B0O1l2 and the word Hello")
        assertEquals(listOf("SN-8B0O1l2"), r)
        assertTrue(OcrGuard.riskyTokens("The cat sat on the mat").isEmpty())
    }
}

class SettingsTest {
    @Test fun clamps() {
        val s = AiSettings(temperature = 9f, contextSize = 999_999, threads = 99, maxTokens = 1).sanitized()
        assertEquals(2f, s.temperature); assertEquals(32768, s.contextSize); assertEquals(8, s.threads); assertEquals(16, s.maxTokens)
        assertEquals(4096, AiSettings(contextSize = 4300).sanitized().contextSize)
    }
    @Test fun autoThreads() { assertEquals(6, AiSettings.autoThreads(8)); assertEquals(4, AiSettings.autoThreads(6)); assertEquals(3, AiSettings.autoThreads(4)) }
    @Test fun defaultsAreSafe() { val d = AiSettings(); assertFalse(d.useGpu); assertEquals(4096, d.contextSize) }
}

class HistoryTest {
    @Test fun keepsOnlyLatestImage() {
        val msgs = listOf(ChatMsg("user", "a", listOf("/1.jpg")), ChatMsg("assistant", "x"), ChatMsg("user", "b", listOf("/2.jpg")))
        val t = History.trimImages(msgs)
        assertTrue(t[0].images.isEmpty()); assertEquals(listOf("/2.jpg"), t[2].images)
    }
    @Test fun fitsContextKeepsSystem() {
        val long = "x".repeat(4000)
        val msgs = listOf(ChatMsg("system", "sys")) + (1..10).flatMap { listOf(ChatMsg("user", long), ChatMsg("assistant", long)) } + ChatMsg("user", "last")
        val f = History.fitToContext(msgs, 4096, 1024)
        assertEquals("system", f.first().role); assertEquals("last", f.last().text); assertEquals("user", f[1].role)
        assertTrue(f.size < msgs.size)
    }
}

class OpenAiTest {
    @Test fun parsesTextAndImages() {
        val body = """{"model":"x","stream":true,"temperature":0.2,"max_tokens":50,
          "messages":[{"role":"system","content":"be nice"},
          {"role":"user","content":[{"type":"text","text":"what is this"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}]}"""
        val r = OpenAi.parseChatRequest(body)
        assertTrue(r.stream); assertEquals(0.2f, r.temperature); assertEquals(50, r.maxTokens)
        assertEquals(2, r.messages.size); assertEquals("what is this", r.messages[1].text)
        assertEquals("data:image/png;base64,AAAA", r.messages[1].images.single().url)
        assertEquals("image/png" to "AAAA", OpenAi.parseDataUrl(r.messages[1].images.single().url))
    }
    @Test fun rejectsBadBodies() {
        listOf("nope", "{}", """{"messages":[]}""", """{"messages":[{"role":"assistant","content":"hi"}]}""", """{"messages":[{"role":"wizard","content":"hi"}]}""")
            .forEach { b -> try { OpenAi.parseChatRequest(b); fail("should reject $b") } catch (_: OpenAi.BadRequest) {} }
    }
    @Test fun buildsValidJson() {
        val c = Json.parseToJsonElement(OpenAi.completion("id1", "m", 1, "hi \"there\"", null, "stop", 3, 2)).jsonObject
        assertEquals("hi \"there\"", c["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(5, c["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
        val ch = Json.parseToJsonElement(OpenAi.chunk("id1", "m", 1, "tok", null, role = true)).jsonObject
        assertEquals("tok", ch["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(2, Json.parseToJsonElement(OpenAi.models(listOf("a", "b"), 1)).jsonObject["data"]!!.jsonArray.size)
    }
}

class MiniHttpTest {
    @Test fun parsesPostWithBodyAndAuth() {
        val body = """{"a":1}"""
        val raw = "POST /v1/chat/completions?x=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret123\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val r = MiniHttp.read(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("POST", r.method); assertEquals("/v1/chat/completions", r.path); assertEquals(body, r.bodyText)
        assertTrue(MiniHttp.authorized(r, "secret123")); assertFalse(MiniHttp.authorized(r, "other")); assertTrue(MiniHttp.authorized(r, ""))
    }
    @Test fun chunkedBody() {
        val raw = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n"
        assertEquals("abcde", MiniHttp.read(ByteArrayInputStream(raw.toByteArray()))!!.bodyText)
    }
    @Test fun rejectsHugeBody() {
        val raw = "POST / HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n"
        try { MiniHttp.read(ByteArrayInputStream(raw.toByteArray())); fail() } catch (e: MiniHttp.HttpError) { assertEquals(413, e.status) }
    }
    @Test fun writesJson() {
        val out = ByteArrayOutputStream(); MiniHttp.writeJson(out, 200, "{}")
        val s = out.toString(); assertTrue(s.startsWith("HTTP/1.1 200 OK")); assertTrue(s.endsWith("\r\n\r\n{}"))
    }
}

class ThemeTest {
    @Test fun fromHexAndKeywords() {
        val t = ThemeSpec.fromText("Ocean", "Try #00B4D8 for the accent")!!
        assertEquals(0xFF00B4D8, t.primary)
        assertTrue(ThemeSpec.contrast(t.primary, t.background) >= 4.5)
        assertEquals(0xFFC77DFF, ThemeSpec.fromText("x", "something purple and moody")!!.primary)
        assertNull(ThemeSpec.fromText("x", "no colors here"))
    }
    @Test fun darkAccentFallsBackToReadable() {
        val t = ThemeSpec.fromText("dark", "#050505")!!
        assertTrue(ThemeSpec.contrast(t.primary, t.background) >= 4.5)
    }
    @Test fun presetsReadable() { ThemeSpec.presets.forEach { assertTrue(it.name, ThemeSpec.contrast(it.primary, it.background) >= 4.5) } }
}

class BizzyTest {
    @Test fun parsesCommands() {
        assertEquals(Bizzy.Command.Remember("my Pixel has 512 GB"), Bizzy.parse("Remember that my Pixel has 512 GB."))
        assertEquals(Bizzy.Command.Forget("pixel"), Bizzy.parse("forget pixel"))
        assertEquals(Bizzy.Command.ListMemory, Bizzy.parse("what do you remember?"))
        assertEquals(Bizzy.Command.None, Bizzy.parse("how do I load a model"))
    }
    @Test fun systemPromptIncludesMemory() {
        val p = Bizzy.systemPrompt("base", listOf("likes neon"), "Chat")
        assertTrue(p.contains("likes neon")); assertTrue(p.contains("\"Chat\""))
    }
    @Test fun tipsWithoutModel() { assertTrue(Bizzy.tip("Chat", false, false).contains("Models")) }
}
