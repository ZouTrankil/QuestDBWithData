package com.zoutrankil.batch;

import com.zoutrankil.data.config.MainStrategyBatchProperties;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.factory.DisposableBean;

/** Starts after the main Web context is ready; a finite CLI never constructs this lifecycle bean. */
public final class MainStrategyDailyBatchRuntime implements ApplicationListener<ApplicationReadyEvent>,DisposableBean {
    private final ApplicationContext parent;
    private final MainStrategyBatchProperties properties;
    private final MainStrategyDailyWork work;
    private volatile AnnotationConfigApplicationContext child;
    private volatile String startupError;
    public MainStrategyDailyBatchRuntime(ApplicationContext parent,MainStrategyBatchProperties properties,MainStrategyDailyWork work) {
        this.parent=parent;this.properties=properties;this.work=work;
    }
    @Override public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if(event.getApplicationContext()!=parent || child!=null) return;
        var context=new AnnotationConfigApplicationContext();
        try {
            context.setParent(parent);context.setId(parent.getId()+":main-strategy-batch");
            // Resolve the unique properties and Java pipeline directly from the parent.
            // Additional aliases in the child would become duplicate autowire candidates.
            context.register(MainStrategyDailyChildConfiguration.class);context.refresh();
            var coordinator=context.getBean(MainStrategyDailyCoordinator.class);
            coordinator.install();child=context;
            if(properties.startupCatchup()) coordinator.catchup("startup:"+java.util.UUID.randomUUID());
        } catch(Exception error) {
            startupError=error.getClass().getSimpleName()+": "+java.util.Objects.toString(error.getMessage(),"");
            child=null;
            context.close();
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Main-strategy Batch runtime failed to initialize",error);
        }
    }
    private MainStrategyDailyCoordinator coordinator() {
        var current=child;
        if(current==null) throw new IllegalStateException("Main-strategy Batch runtime is unavailable: "+startupError);
        return current.getBean(MainStrategyDailyCoordinator.class);
    }
    public Map<String,Object> status() throws Exception {
        if(child==null) return Map.of("job",MainStrategyDailyWork.JOB,"status","UNAVAILABLE",
                "reason",startupError==null?"Web context is not ready":startupError);
        return coordinator().status();
    }
    public Map<String,Object> detail(String instance) { return coordinator().detail(instance); }
    public Map<String,Object> catchup(String requestId) { return coordinator().catchup(requestId); }
    public Map<String,Object> reconcilePublication(String instance,String stage,String runId,boolean writerStopped,String requestId)throws Exception{return coordinator().reconcilePublication(instance,stage,runId,writerStopped,requestId);}
    public Map<String,Object> setEnabled(boolean enabled) throws Exception { return coordinator().setEnabled(enabled); }
    @Override public synchronized void destroy() {
        if(child!=null) {
            child.getBean(MainStrategyDailyCoordinator.class).close();
            child.close();child=null;
        }
    }
}
