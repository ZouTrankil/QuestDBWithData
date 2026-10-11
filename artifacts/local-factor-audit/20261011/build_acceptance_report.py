import json,datetime
from pathlib import Path
p=Path('artifacts/local-factor-audit/20261011')
def read(name):return json.loads((p/name).read_text())
j=read('shared-final-report.json');py=read('normalized-python-repeat-profile.json');sp=read('structural-python-profile.json')
def terminal(name):
 records=[]
 for line in (p/name).read_text(encoding='utf-8-sig',errors='replace').splitlines():
  if line.startswith('{"runId":'):
   records.append(json.loads(line))
 return records[-1] if records else {'state':'RUNNING'}
report={
 'updatedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'completeMigration':False,
 'referenceDate':'2026-09-30',
 'sharedPanel':{'sourceMarketRows':j['sourceRows'],'sourceSecurities':j['securities'],'outputSecurityDates':j['requestedSecurityDateRows'],'factors':16,'observations':j['observationRows'],'parity':read('shared-final-parity.json')},
 'benchmark':{'scope':'Single-date output from identical frozen full-market daily inputs; current Python pandas path; excludes startup, export and publication.',
  'javaDailyComputeSeconds':j['computeMillis']/1000,'pythonDailyComputeSeconds':py['computeSeconds'],'dailyComputeRatio':py['computeSeconds']/(j['computeMillis']/1000),
  'javaDailyReadSeconds':j['readMillis']/1000,'pythonDailyReadAndNormalizeSeconds':py['readSeconds'],
  'javaFinancialIndicatorComputeSeconds':j['financialComputeMillis']/1000,
  'javaStructuralComputeSeconds':j['statementComputeMillis']/1000,'pythonStructuralComputeSeconds':sp['computeSeconds'],
  'memoryMeasurement':'Individual JVM memory pool peaks, not process RSS or simultaneous total heap peak.',
  'javaMemoryPoolPeakBytes':j['memoryPoolPeakBytes']},
 'publication':{'financial':terminal('financial-resume.log'),'financialPhysical':read('financial-publish-audit.json'),
                'structural':terminal('structural-publish.log'),'structuralPhysical':read('structural-publish-audit.json'),
                'sourceComplete':False,'automaticPostCloseEnabled':False},
 'incremental':{'changedRowsOnly':True,'resumeReadbackVerified':True,'recoveredFinancialRows':9000,
  'sourceAndCodeFingerprint':True,'fullAutomaticComputeCoveragePlannerIntegrated':False},
 'poolPipeline':{'sharedRawArrays':True,'independentPoolPreprocessing':True,'boundedProjectionCache':True,'realPitMembershipIntegrated':False},
 'nativeBindings':read('native-binding-audit.json'),
 'remaining':['Real PIT pool membership and pool-level acceptance','Persistent compute coverage, dirty-window propagation and daily orchestration','L2, event and ETF producer integration','Source completeness certification and missing-source repair','Factor metric/evaluation and post-close chain migration']}
if (p/'market-state-live-parity.json').exists():
 report['marketState']={'parity':read('market-state-live-parity.json'),'publication':terminal('market-state-publish.log')}
 report['verifiedFactorCount']=26
 report['verifiedObservationRows']=j['observationRows']+10
 report['publication']['combinedStockPhysical']=read('shared-final-publish-audit.json')
 if (p/'market-state-publish-audit.json').exists():report['marketState']['physical']=read('market-state-publish-audit.json')
if (p/'pit-selected-parity.json').exists():
 report['pitOnlySelection']={'profile':read('pit-selected-report.json'),'parity':read('pit-selected-parity.json')}
if (p/'live-pool-membership-parity.json').exists():
 report['poolPipeline'].update({'realPitMembershipIntegrated':True,
  'membershipParity':read('live-pool-membership-parity.json'),
  'valueParity':read('live-pool-value-parity.json'),
  'industryExposureParity':read('stock-exposure-parity.json'),
  'automaticComputeCoverageIntegrated':False})
 report['remaining'][0]='Repair missing index membership and certify pool input coverage'
if (p/'pool-coverage-audit.json').exists():
 report['poolPipeline']['persistentArtifactCoverage']=read('pool-coverage-audit.json')
if (p/'native-pipeline-audit.json').exists():
 report['nativePipeline']=read('native-pipeline-audit.json')
if (p/'pipeline-reuse-audit.json').exists():
 report['incremental']['exactInputComputeReuse']=read('pipeline-reuse-audit.json')
if (p/'events-parity.json').exists():
 report['announcementEvents']={'parity':read('events-parity.json'),'profile':read('events-report.json')}
 if (p/'events-publish-audit.json').exists():
  report['announcementEvents']['physical']=read('events-publish-audit.json')
  report['announcementEvents']['publication']=terminal('events-publish.log')
  report['verifiedFactorCount']+=5
  report['verifiedObservationRows']+=report['announcementEvents']['physical']['expectedRows']
if (p/'margin-factors-parity.json').exists():
 report['marginFactors']={'parity':read('margin-factors-parity.json'),'profile':read('margin-factors-report.json'),'published':False}
 if (p/'margin-factors-publish-audit.json').exists():
  report['marginFactors'].update({'published':True,'physical':read('margin-factors-publish-audit.json'),'publication':terminal('margin-factors-publish.log')})
  report['verifiedFactorCount']+=2
  report['verifiedObservationRows']+=report['marginFactors']['physical']['expectedRows']
if (p/'l2-factor-source-audit.json').exists():
 report['l2SourceAudit']=read('l2-factor-source-audit.json')
if (p/'l2-shared-panel-parity.json').exists():
 report['l2SharedPanel']={'parity':read('l2-shared-panel-parity.json'),'published':False,'poolIntegrationComplete':False}
 report['remaining'][2]='L2 source-scope and pool/publication integration; ETF producer integration'
if (p/'l2-pool-parity.json').exists():
 report['l2SharedPanel'].update({'poolParity':read('l2-pool-parity.json'),'diagnosticPoolIntegrated':True,
                               'sourceScopeGateImplemented':True,'formalPublicationIntegrated':False})
 report['remaining'][2]='L2 formal publication and all-factor pool acceptance; ETF producer integration'
if (p/'l2-observation-parity.json').exists():
 report['l2SharedPanel']['canonicalPublicationParity']=read('l2-observation-parity.json')
if (p/'l2-publish-audit.json').exists():
 report['l2SharedPanel'].update({'physical':read('l2-publish-audit.json'),'publication':terminal('l2-publish-run.log'),
                               'published':True,'formalPublicationIntegrated':True})
 report['verifiedFactorCount']+=read('l2-observation-parity.json')['factors']
 report['verifiedObservationRows']+=read('l2-publish-audit.json')['expectedRows']
 report['remaining'][2]='L2 all-factor publication acceptance and automatic pipeline integration; ETF producer integration'
if (p/'l2-all-pool-parity.json').exists():
 report['l2SharedPanel']['allFactorPoolParity']=read('l2-all-pool-parity.json')
if (p/'l2-all-bundle-parity.json').exists():
 report['l2SharedPanel']['allFactorBundleParity']=read('l2-all-bundle-parity.json')
if (p/'l2-all-publication/publication.json').exists():
 report['l2SharedPanel']['allFactorPublicationProgress']=read('l2-all-publication/publication.json')
report['validation']={'compilePassed':'BUILD SUCCESSFUL' in (p/'factor-acceptance-compile.log').read_text(errors='replace'),
 'targetedLedgerTests':{'tests':2,'failures':0,'errors':0,'report':'data-app/build/test-results/test/TEST-com.zoutrankil.data.repository.SyncRunPartialRecoveryTest.xml'},
 'fullTestSuiteRun':False,
 'liveMixedFactorPageReadback':{'rows':250,'matches':250,'log':'joined-preflight-readback.log'},
 'marketStateSyntheticParity':read('market-state-parity.json')}
(p/'factor-pipeline-acceptance.json').write_text(json.dumps(report,indent=2,ensure_ascii=False));print(json.dumps({'dailyComputeRatio':report['benchmark']['dailyComputeRatio'],'financial':report['publication']['financial']['state'],'structural':report['publication']['structural']['state'],'completeMigration':False}))
