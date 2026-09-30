package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.service.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Explicit catalog discovery, offline frozen planning, serial execution and stopped-writer recovery. */
final class IndexMembershipCommands {
    private IndexMembershipCommands() {}
    static void execute(String command,Map<String,String> options,IndexMembershipJobService owner) throws Exception {
        if(owner==null) throw new IllegalArgumentException("Registered membership owner required");
        var json=JobDefinitionJson.mapper();
        if(command.equals("discover-index-member-catalog")) {
            if(!options.keySet().equals(Set.of("--output-directory"))) throw new IllegalArgumentException("Explicit output-directory required");
            System.out.println(json.writeValueAsString(owner.discover(Path.of(options.get("--output-directory")))));return;
        }
        if(command.equals("finish-index-member-child") || command.equals("finish-index-member-prepared")) {
            if(!options.keySet().equals(Set.of("--run","--writer-stopped")) || !"true".equals(options.get("--writer-stopped")))
                throw new IllegalArgumentException("Explicit child run and writer-stopped true required");
            var result=command.equals("finish-index-member-prepared")
                    ?owner.finishPreparedChild(options.get("--run"),true)
                    :owner.finishInterruptedChild(options.get("--run"),true);
            System.out.println(json.writeValueAsString(result));return;
        }
        var required=Set.of("--classification-receipt","--classification-sha256","--industries","--selection","--logical-date");
        var allowed=new HashSet<>(required);allowed.add("--resume-from");allowed.add("--writer-stopped");
        if(!options.keySet().containsAll(required) || !allowed.containsAll(options.keySet()))
            throw new IllegalArgumentException("Explicit classification receipt/SHA, industries, selection and logical-date required");
        boolean plan=command.equals("plan-index-member-job");
        if(!plan && !command.equals("run-index-member-job")) throw new IllegalArgumentException("Unknown membership command");
        if(plan && (options.containsKey("--resume-from") || options.containsKey("--writer-stopped")))
            throw new IllegalArgumentException("Resume and writer-stopped are execution options");
        if(options.containsKey("--writer-stopped") && (!options.containsKey("--resume-from") || !"true".equals(options.get("--writer-stopped"))))
            throw new IllegalArgumentException("Stopped coordinator recovery needs resume-from and writer-stopped true");
        var codes=Arrays.stream(options.get("--industries").split(",",-1)).map(String::trim).toList();
        var request=owner.planFromReceipt(Path.of(options.get("--classification-receipt")),options.get("--classification-sha256"),codes,
                IndexMembershipSource.Selection.valueOf(options.get("--selection")),LocalDate.parse(options.get("--logical-date")));
        if(plan) {
            System.out.println(json.writeValueAsString(Map.of("status","PLANNED","executed",false,"dataVerified",false,
                    "request",json.readTree(SyncRequestIdentity.snapshotJson(request)))));return;
        }
        var result=!options.containsKey("--resume-from")?owner.run(request)
                :options.containsKey("--writer-stopped")?owner.resumeStopped(request,options.get("--resume-from"),true)
                :owner.resume(request,options.get("--resume-from"));
        System.out.println(json.writeValueAsString(result));
        if(result.errorCode()!=null || !Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(result.state()))
            throw new IncompleteCommandException("Membership batch incomplete: "+result.state());
    }
}
