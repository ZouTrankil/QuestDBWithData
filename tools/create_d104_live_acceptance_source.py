"""Generate D104-specific acceptance source from the reviewed preceding harness; no DB operations."""
from pathlib import Path

ROOT = Path(__file__).absolute().parent.parent
source = ROOT / 'src/test/java/com/zoutrankil/data/config/EquityStyleMonthlyLiveAcceptanceTest.java'
target = ROOT / 'src/test/java/com/zoutrankil/data/config/MacroCoreMonthlyLiveAcceptanceTest.java'
assert not target.exists()
text = source.read_text(encoding='utf-8-sig')
for left, right in [('EquityStyle','MacroCore'), ('equityStyle','macroCore'), ('equity_style','macro_core'),
                    ('equity-style','macro-core'), ('D103','D104'), ('d103','d104'),
                    ('20261006','20261007'), ('18842','18852'), ('19030','19040')]:
    text = text.replace(left, right)
text = text.replace('LocalDate.of(2026,10,6)', 'LocalDate.of(2026,10,7)')
text = text.replace('private static final String PREFLIGHT_SHA="61a3e91753074598b6db0d5bdc7867ff45d90d144628629a36316a0bf091b0db";',
                    'private static final String PREFLIGHT_SHA=required("D104_PREFLIGHT_SHA");')
start = text.index('        if(initial) {\n            assertEquals("VERIFIED_INITIAL_SOURCE_BY_READONLY_RECONCILIATION"')
stop = text.index('        var evidence=new LinkedHashMap<String,Object>();', start)
text = text[:start] + '''        assertEquals(initial?"VERIFIED_ISOLATED_SOURCE_INITIAL":"VERIFIED_ISOLATED_SOURCE_INCREMENT",
                fixtureEvidence.required("status").asText());
        assertFalse(fixtureEvidence.required("automatic_retry").asBoolean());
        assertEquals(0,fixtureEvidence.required("formal_writes").asInt());
        assertEquals(0,fixtureEvidence.required("private_output_writes").asInt());
        var startup=DIRECTORY.resolve("private-server-start-20261007.json");
        assertEquals(required("D104_STARTUP_SHA"),sha(startup));
        var privateIdentity=JobDefinitionJson.mapper().readTree(startup.toFile());
        assertEquals("D104",privateIdentity.required("task_id").asText());
''' + text[stop:]
text = text.replace('            assertEquals(initial?32L:48L,jdbc.queryForObject("SELECT count() FROM index_monthly",Long.class));\n', '')
text = text.replace('new MacroCoreMonthlyJobService(jdbc,properties,"index_monthly",TABLE,LEDGER)',
                    'new MacroCoreMonthlyJobService(jdbc,properties,TABLE,LEDGER)')
text = text.replace('initial?32:48', 'initial?23:28')
start = text.index('            var admittedState=fixtureEvidence.required("private_after").required("index_monthly");')
stop = text.index('            evidence.put("admitted_source",admittedSource);', start)
text = text[:start] + '''            var formalSource=new MacroCoreMonthlySource(formal).read(JUNE,initial?JULY:AUGUST);
            assertEquals(formalSource.rawFingerprint(),admittedSource.rawFingerprint(),
                    "Complete six-source raw window and SF context must equal real formal capture");
            assertRows(admittedSource.rows(),formalSource.rows());
            evidence.put("formal_source",formalSource);
''' + text[stop:]
text = text.replace('List.of(repository,(DatasetImplementation)()->IndexMonthlyDataset.DEFINITION,(DatasetImplementation)()->IndexCatalogDataset.DEFINITION)',
                    'List.of(repository)')
text = text.replace('expected.size()*30', 'expected.size()*9').replace('expected.size()*29', 'expected.size()*8')
text = text.replace('Exact month/null/29-bit values', 'Exact month/null/8-double-slot values')
text = text.replace('List.of("index_monthly","macro_core_monthly")',
                    'List.of("cn_cpi","cn_ppi","cn_pmi","cn_m","cn_gdp","sf_month","macro_core_monthly")')
text = text.replace("') LIMIT 31", "') LIMIT 61")
text = text.replace('assertEquals(sourceBefore,owner.source().read(JUNE,initial?JULY:AUGUST));',
                    'assertEquals(sourceBefore,owner.source().read(JUNE,initial?JULY:AUGUST));\n            assertEquals(formalSource,new MacroCoreMonthlySource(formal).read(JUNE,initial?JULY:AUGUST));')
assert 'index_monthly' not in text
with target.open('x', encoding='utf-8', newline='\n') as stream:
    stream.write(text)
print(target)
