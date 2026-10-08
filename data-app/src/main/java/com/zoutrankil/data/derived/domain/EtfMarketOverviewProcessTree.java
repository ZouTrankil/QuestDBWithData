package com.zoutrankil.data.derived.domain;

import java.time.Instant;
import java.util.*;

/** Original OS birth identities and a finite, unique parent chain. */
public final class EtfMarketOverviewProcessTree {
    private EtfMarketOverviewProcessTree() {}
    public record BridgeNode(long pid, Instant birth, Long parentPid) {}
    public static boolean parentChainProved(long bridgePid,long rootPid,Instant rootBirth,Collection<BridgeNode> nodes){
        if(rootBirth==null)return false;if(bridgePid==rootPid)return true;
        var byPid=new HashMap<Long,List<BridgeNode>>();for(var node:nodes)byPid.computeIfAbsent(node.pid(),ignored->new ArrayList<>()).add(node);
        var seen=new HashSet<Long>();long cursor=bridgePid;
        while(cursor!=rootPid){
            if(seen.size()>=64||!seen.add(cursor))return false;var matches=byPid.get(cursor);
            if(matches==null||matches.size()!=1)return false;var node=matches.getFirst();if(node.birth()==null||node.parentPid()==null)return false;
            Instant parentBirth=rootBirth;
            if(node.parentPid()!=rootPid){var parents=byPid.get(node.parentPid());if(parents==null||parents.size()!=1||parents.getFirst().birth()==null)return false;parentBirth=parents.getFirst().birth();}
            if(parentBirth.isAfter(node.birth()))return false;cursor=node.parentPid();
        }
        return true;
    }
}
