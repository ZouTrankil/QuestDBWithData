package com.zoutrankil.architecture;

import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.nio.file.*;
import java.io.File;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 读取实际编译产物检查模块边界，连方法体里的全限定名引用也不能绕过。 */
class MarketModuleArchitectureTest {
    private Map<String,Path> compiledOwners() throws Exception {
        var owners=new HashMap<String,Path>();
        for (var value:System.getProperty("architecture.mainClasses").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            var dir=Path.of(value);
            if (!Files.isDirectory(dir)) continue;
            try (var files=Files.walk(dir)) {
                for (var file:files.filter(p->p.toString().endsWith(".class")).toList()) {
                    String name=ClassFile.of().parse(Files.readAllBytes(file)).thisClass().asInternalName();
                    assertNull(owners.putIfAbsent(name,dir),"类出现在两个模块："+name);
                }
            }
        }
        assertFalse(owners.isEmpty());return owners;
    }
    private String normalized(Path path) { return path.toString().replace('\\','/'); }
    private List<String> references(Path dir,String type) throws Exception {
        var result=new ArrayList<String>();
        var model=ClassFile.of().parse(Files.readAllBytes(dir.resolve(type+".class")));
        for (var entry:model.constantPool()) if (entry instanceof ClassEntry clazz) {
            String ref=clazz.asInternalName();
            if (!ref.startsWith("[")) result.add(ref);
        }
        return result;
    }
    @Test void coreBytecodeUsesOnlyJdkAndCoreContracts() throws Exception {
        var owners=compiledOwners();int checked=0;
        for (var entry:owners.entrySet()) if (normalized(entry.getValue()).contains("/modules/data-core/")) {
            checked++;
            for (String ref:references(entry.getValue(),entry.getKey())) {
                var owner=owners.get(ref);
                assertTrue(owner==null ? ref.startsWith("java/")||ref.startsWith("javax/")||ref.startsWith("jdk/")
                        : normalized(owner).contains("/modules/data-core/"),"core 引用了外部实现："+ref);
            }
        }
        assertTrue(checked>0);
        assertFalse(owners.entrySet().stream().anyMatch(e->normalized(e.getValue()).contains("/modules/data-core/")
                &&(e.getKey().contains("Tushare")||e.getKey().contains("L2Daily")||e.getKey().contains("DailySemantics"))));
    }
    @Test void engineBytecodeDoesNotDependOnMarketOrApplicationClasses() throws Exception {
        var owners=compiledOwners();int checked=0;
        for (var entry:owners.entrySet()) if (normalized(entry.getValue()).contains("/modules/data-engine/")) {
            checked++;
            for (String ref:references(entry.getValue(),entry.getKey())) {
                var owner=owners.get(ref);
                if (owner!=null) assertFalse(normalized(owner).matches(".*?/modules/(market-[^/]+|data-app|batch-app)/.*"),
                        "引擎反向引用市场或入口："+entry.getKey()+" -> "+ref);
            }
        }
        assertTrue(checked>0);
    }
    @Test void commonApiLibraryDoesNotReferenceMarketsOrOwnTheirEntrypoints() throws Exception {
        var owners = compiledOwners(); int checked = 0;
        for (var entry : owners.entrySet()) if (normalized(entry.getValue()).contains("/modules/data-app/")) {
            checked++;
            assertNotEquals("com/zoutrankil/data/QuestDataApplication", entry.getKey());
            for (String ref : references(entry.getValue(), entry.getKey())) {
                var owner = owners.get(ref);
                if (owner != null) assertFalse(normalized(owner).matches(".*?/modules/(market-[^/]+|batch-app)/.*"),
                        "公共 API 反向引用市场或应用：" + entry.getKey() + " -> " + ref);
            }
        }
        assertTrue(checked > 0);
        var entrypoint = owners.get("com/zoutrankil/data/QuestDataApplication");
        assertNotNull(entrypoint);
        assertTrue(normalized(entrypoint).contains("/modules/market-cn/data-app/"));
    }
    @Test void marketImplementationsDoNotCallEachOther() throws Exception {
        var owners=compiledOwners();
        for (var entry:owners.entrySet()) {
            String own=market(normalized(entry.getValue()));if (own==null) continue;
            for (String ref:references(entry.getValue(),entry.getKey())) {
                var owner=owners.get(ref);String other=owner==null?null:market(normalized(owner));
                assertTrue(other==null||other.equals(own),"市场业务跨模块调用："+entry.getKey()+" -> "+ref);
            }
        }
    }
    private String market(String path) {
        var matcher=java.util.regex.Pattern.compile("/modules/(market-(?:cn|us|btc))/").matcher(path);
        return matcher.find()?matcher.group(1):null;
    }
    @Test void persistedQuartzClassNamesRemainLoadableWithoutInitializingApps() throws Exception {
        for (String name:List.of("com.zoutrankil.batch.ScheduledLaunch","com.zoutrankil.batch.schedule.ScheduledLaunch",
                "com.zoutrankil.batch.MainStrategyDailyScheduledLaunch","com.zoutrankil.batch.strategy.MainStrategyDailyScheduledLaunch",
                "com.zoutrankil.batch.storage.SqliteQuartzDelegate"))
            assertNotNull(Class.forName(name,false,getClass().getClassLoader()));
    }
}
