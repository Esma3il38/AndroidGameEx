package com.techted89.gameex

import org.junit.Test
import java.lang.reflect.Modifier

class NativeScannerJniTest {
    @Test
    fun testNativeMethods() {
        val clazz = NativeScanner::class.java
        for (method in clazz.declaredMethods) {
            if (Modifier.isNative(method.modifiers)) {
                println("Native Method: ${method.name}")
            }
        }
    }
}
