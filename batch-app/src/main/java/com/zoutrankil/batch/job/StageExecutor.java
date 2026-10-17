package com.zoutrankil.batch.job;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.CompletionEvidence;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.external.ExternalComputation;
import com.zoutrankil.batch.postclose.PostCloseGraph;

/** 实现方必须提供实际执行结果的证据，不能仅凭进程退出码为 0 判断完成。 */
public interface StageExecutor {
    record Result(BusinessState state, CompletionEvidence evidence, String reason) {
        public Result {
            if (state == null || (state.ready() && evidence == null))
                throw new IllegalArgumentException("Ready requires evidence");
        }
    }
    Result execute(RunRequest request, PostCloseGraph.Stage stage);

    static StageExecutor disconnected(ExternalComputation external) {
        return (request, stage) -> {
            if (!stage.external()) return new Result(BusinessState.BLOCKED, null, "producer-not-connected:"+stage.id());
            var observation=external.observe(request, stage.id());
            var state=ExternalComputation.validate(request, stage.id(), observation);
            return new Result(state, state.ready() ? observation.result() : null, observation.reason());
        };
    }
}
