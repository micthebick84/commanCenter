package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.workerdaemon.UsagePathClassifier.Category;
import com.hamonsoft.netismaker.workerdaemon.UsagePathClassifier.Roots;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.hamonsoft.netismaker.workerdaemon.UsagePathClassifier.classify;
import static com.hamonsoft.netismaker.workerdaemon.UsagePathClassifier.normalize;
import static org.assertj.core.api.Assertions.assertThat;

class UsagePathClassifierTest {

    private static final List<String> ROOT = List.of(normalize("C:\\Users\\mic\\netis-maker"));

    @Test
    void 정규화() {
        assertThat(normalize("C:\\Users\\Mic\\netis-maker\\")).isEqualTo("c:/users/mic/netis-maker");
        assertThat(normalize("/c/Users/mic/x")).isEqualTo("c:/users/mic/x");
        assertThat(normalize("\\\\?\\C:\\a")).isEqualTo("c:/a");
        assertThat(normalize("/Users/Mic/x//y/")).isEqualTo("/users/mic/x/y");
    }

    @Test
    void 폴더_경계() {
        assertThat(classify("/c/Users/MIC/netis-maker/interviews/s", ROOT)).isEqualTo(Category.SYMPHONY);
        assertThat(classify("C:\\Users\\mic\\netis-maker\\close\\task-10", ROOT)).isEqualTo(Category.SYMPHONY);
        assertThat(classify("C:\\Users\\mic\\netis-maker", ROOT)).isEqualTo(Category.SYMPHONY);
        assertThat(classify("C:\\Users\\mic\\netis-maker-old\\x", ROOT)).isEqualTo(Category.DIRECT);
    }

    @Test
    void cwd가_없으면_DIRECT() {
        assertThat(classify(null, ROOT)).isEqualTo(Category.DIRECT);
        assertThat(classify("  ", ROOT)).isEqualTo(Category.DIRECT);
    }

    @Test
    void 기본_루트() {
        Roots r = UsagePathClassifier.roots("C:\\w\\wt", "C:\\d\\repos", "C:\\Users\\mic",
                List.of("~/netis-remote-worker"));
        assertThat(r.accepted()).contains("c:/w/wt", "c:/d/repos", "c:/d/close", "c:/users/mic/netis-maker",
                "c:/users/mic/netis-remote-worker");
        assertThat(r.ignored()).isEmpty();
    }

    @Test
    void 홈과_루트는_무시() {
        Roots r = UsagePathClassifier.roots("C:\\w\\wt", "C:\\d\\repos", "C:\\Users\\mic",
                List.of("~", "C:\\", "/", ""));
        assertThat(r.ignored()).hasSize(4);
        assertThat(r.accepted()).doesNotContain("c:/users/mic", "c:", "/", "");
    }

    @Test
    void 홈_펼치기() {
        assertThat(UsagePathClassifier.expandHome("~", "/home/a")).isEqualTo("/home/a");
        assertThat(UsagePathClassifier.expandHome("~/x", "/home/a")).isEqualTo("/home/a/x");
        assertThat(UsagePathClassifier.expandHome("~\\x", "C:\\Users\\a")).isEqualTo("C:\\Users\\a/x");
        assertThat(UsagePathClassifier.expandHome("D:\\x", "/home/a")).isEqualTo("D:\\x");
    }
}
