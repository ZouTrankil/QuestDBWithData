package com.zoutrankil.batch;

/** Implementations must supply physical evidence, never infer completion from exit 0. */
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
