package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.node.*;
import com.zoutrankil.data.domain.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Synthetic protocol fixtures only: no network, process or original owner invocation. */
public final class EtfMarketOverviewCacheTestFixtures {
    /** Test-only explicit transport assembly; production Gateway has no hidden process factory. */
    public static class TestGateway extends EtfMarketOverviewCacheOwnerGateway {
        public TestGateway(Config config) {
            super(config, new com.zoutrankil.data.derived.storage.EtfMarketOverviewOwnerProcess(
                    config.pythonExecutable(), config.bridgeScript(), config.timeout()));
        }
        public TestGateway(Config config, com.zoutrankil.data.derived.storage.EtfMarketOverviewOwnerProcess.ProcessLauncher launcher) {
            super(config, new com.zoutrankil.data.derived.storage.EtfMarketOverviewOwnerProcess(
                    config.pythonExecutable(), config.bridgeScript(), config.timeout(), launcher));
        }
    }
    public static final LocalDate DAY=LocalDate.of(2026,9,17);
    public static final String SHA="a".repeat(64),SOURCE="b".repeat(64),TARGET="questdb-"+"c".repeat(64),UNIT="d".repeat(64),DIGEST="e".repeat(64);
    private EtfMarketOverviewCacheTestFixtures(){}
    public static EtfMarketOverviewCacheOwnerGateway.Config config(Path artifacts){return new EtfMarketOverviewCacheOwnerGateway.Config(Path.of("python.exe"),Path.of("tools/d101_etf_cache_owner_bridge.py"),artifacts,Path.of("var/d101-isolated-questdb"),1234,Duration.ofSeconds(1),50000);}
    public static ObjectNode preview(EtfMarketOverviewCacheOwnerGateway.Config config,boolean known,boolean cache){
        var json=JobDefinitionJson.mapper();var value=json.createObjectNode();
        value.put("protocol_version",1);value.put("operation","preview");value.put("status","PREVIEW_VERIFIED");value.put("dataset_id","etf_market_overview_daily");value.put("trade_date",DAY.toString());value.put("invocation_id","00000000-0000-0000-0000-000000000001");
        var target=value.putObject("target");target.put("private_root",config.privateRoot().toString());target.put("expected_pid",config.expectedPid());target.put("http_port",19020);target.put("pg_port",18832);
        var att=value.putObject("process_attestation");att.put("data_root",config.privateRoot().toString());att.put("pid",config.expectedPid());att.put("host","127.0.0.1");att.put("http_port",19020);att.put("pg_port",18832);
        var sources=value.putObject("source_snapshots");var targets=value.putObject("target_snapshots");int id=1;
        for(String table:EtfMarketOverviewCachePublicationEnvelope.SOURCE_TABLES)sources.set(table,snapshot(id++,"YEAR","timestamp"));
        for(String table:EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES)targets.set(table,snapshot(id++,"MONTH","trade_date"));
        var counts=value.putObject("target_actual_row_counts");for(String table:EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES)counts.put(table,1);
        value.put("source_version",SHA);value.put("sources_fingerprint",SOURCE);value.put("targets_fingerprint","f".repeat(64));value.put("source_fingerprint",UNIT);value.put("target_id",TARGET);value.put("known_source_date",known);
        var census=value.putObject("raw_source_census");
        for(String table:EtfMarketOverviewCachePublicationEnvelope.SOURCE_TABLES){var row=census.putObject(table);row.put("row_count",known?1:0);row.put("complete_key_count",known?1:0);row.putArray("fields").add("timestamp").add("ts_code");row.put("full_field_sha256",SHA);row.put("complete_key_sha256",SHA);row.putObject("null_counts").put("timestamp",0).put("ts_code",0);row.put("scope","synthetic complete fields");}
        var schemas=value.putObject("validated_schemas");var tables=new ArrayList<>(EtfMarketOverviewCachePublicationEnvelope.SOURCE_TABLES);tables.addAll(EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES);
        for(String table:tables){var schema=schemas.putObject(table);schema.put("timestamp",table.startsWith("etf_")&&!table.endsWith("cache")?"timestamp":"trade_date");schema.put("partition",table.endsWith("cache")||table.equals(EtfMarketOverviewCachePublicationEnvelope.COVERAGE)?"MONTH":"YEAR");schema.put("wal",true);schema.put("dedup",true);schema.putObject("physical_types").put("timestamp","TIMESTAMP").put("ts_code","SYMBOL");schema.putArray("upsert_keys").add("timestamp").add("ts_code");}
        value.putObject("frozen_python_files_sha256").put("original-owner.py",SHA);
        var rows=value.putArray("expected_cache_records");if(cache){var row=rows.addObject();row.put("trade_date",DAY+"T00:00:00Z");row.put("source_version",SHA);row.put("etf_count",2);row.put("total_share",123.5);row.put("total_size_yi",0.0247);}
        if(known){var receipt=value.putObject("expected_receipt");receipt.put("trade_date",DAY+"T00:00:00Z");receipt.put("dataset_id","etf_market_overview_daily");receipt.put("source_version",SHA);receipt.put("row_count",cache?1:0);receipt.put("content_digest",DIGEST);}else value.putNull("expected_receipt");
        value.put("cache_key_absent_before",!cache);value.put("expected_owner_hits",cache?1:0);value.put("expected_owner_misses",known&&!cache?1:0);value.put("owner_sender_stopped",true);
        value.putObject("bridge_process").put("pid",9999999).put("terminal_response_written",true).put("exit_code",0);return value;
    }
    public static ObjectNode snapshot(int id,String partition,String timestamp){
        var root=JobDefinitionJson.mapper().createObjectNode();var p=root.putObject("physical");
        // Explicit IntNode models the Python JSON parser's ordinary small integer carriers.
        p.put("id",id);p.put("directoryName","table~"+id);p.put("table_txn",2);p.put("table_row_count",1);p.putNull("table_min_timestamp");p.putNull("table_max_timestamp");p.put("partitionBy",partition);p.put("designatedTimestamp",timestamp);p.put("walEnabled",true);p.put("dedup",true);p.put("table_suspended",false);p.put("wal_pending_row_count",0);
        root.putObject("wal").put("sequencerTxn",2).put("writerTxn",2).put("bufferedTxnSize",0).put("suspended",false);root.put("settled",true);return root;
    }
    public static EtfMarketOverviewCachePublicationEnvelope envelope(Path preview,boolean known,boolean cache)throws Exception{
        var config=config(preview.getParent());var json=preview(config,known,cache);return new EtfMarketOverviewCacheTestFixtures.TestGateway(config).parsePreview(json,DAY,json.get("invocation_id").asText(),preview,SHA);
    }
}
