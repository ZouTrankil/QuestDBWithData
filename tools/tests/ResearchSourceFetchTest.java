import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 离线检验真实下载器的容量边界，不请求外部来源。 */
public class ResearchSourceFetchTest {
    public static void main(String[] args) throws Exception {
        var output = new ByteArrayOutputStream();
        long count = ResearchSourceFetch.copyLimited(new ByteArrayInputStream(new byte[100]), output, 100);
        if (count != 100 || output.size() != 100) throw new AssertionError("精确上限应成功");
        output.reset();
        try {
            ResearchSourceFetch.copyLimited(new ByteArrayInputStream(new byte[100001]), output, 100000);
            throw new AssertionError("超限必须失败");
        } catch (IOException expected) {
            if (output.size() > 100000) throw new AssertionError("不能先写入再发现超限");
        }
        try {
            ResearchSourceFetch.copyLimited(new ByteArrayInputStream(new byte[0]), output, 100);
            throw new AssertionError("空源必须失败");
        } catch (IOException expected) { /* 空响应不能生成成功原件。 */ }
        System.out.println("通过：精确上限、流式超限和空源");
    }
}
