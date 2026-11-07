package org.example.project.features.maintenance

import org.example.project.features.maintenance.data.logs.StackTraceText
import kotlin.test.Test
import kotlin.test.assertEquals

class StackTraceTextTest {
    @Test
    fun `jvm, kotlin and javascript frames count as frames`() {
        val frames =
            listOf(
                "\tat org.example.Foo.bar(Foo.kt:12)",
                "\tat java.base/java.lang.Thread.run(Thread.java:833)",
                "\tat org.example.Foo\$bar\$1.invokeSuspend(Foo.kt:20)",
                "\tat org.example.Foo.<init>(Foo.kt:3)",
                "\tat org.example.Foo.native(Native Method)",
                "    at fn (file.js:1:2)",
                "    at async fetchOrders (http://127.0.0.1:8080/pos/app.js:10:5)",
                "    at new Foo (x.js:1:1)",
                "    at Object.<anonymous> (x.js:1:1)",
                "\t... 3 more",
            )
        val split = StackTraceText.split((listOf("java.lang.IllegalStateException: boom") + frames).joinToString("\n"))
        assertEquals(frames.map { it.trim() }, split.frames)
        assertEquals(listOf("java.lang.IllegalStateException: boom"), split.summaryLines)
    }

    @Test
    fun `a line that merely starts with at is not a frame and stays in the scrubbed summary`() {
        val split = StackTraceText.split("Failure\nat anna mueller's place\nat the till of Anna")
        assertEquals(emptyList(), split.frames)
        assertEquals(listOf("Failure", "at anna mueller's place", "at the till of Anna"), split.summaryLines)
    }
}
