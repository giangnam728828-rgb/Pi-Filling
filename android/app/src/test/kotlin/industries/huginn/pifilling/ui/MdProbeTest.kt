package industries.huginn.pifilling.ui
import org.junit.Test
class MdProbeTest {
    @Test fun probe() {
        val t = parseInline("**做了什么**：改了三个文件")
        println("PLAIN=[${t.plain}]")
        println("SPANS=${t.spans}")
    }
}
