package com.zoutrankil.batch.l2;
import java.nio.file.Path;
import java.util.Arrays;
/** 新来源离线清洗入口，不直接写 QuestDB。 */
public final class L2NewSourceBatchCli {
 public static void main(String[] args) throws Exception {
  if(args.length<2)throw new IllegalArgumentException("需要候选映射和批处理参数");
  var request=L2DailyFeatureBatchRequest.parse(Arrays.copyOfRange(args,1,args.length));
  if(request.dates().size()!=1)throw new IllegalArgumentException("一次只验证一个交易日");
  new L2DailyFeatureBatchService(new L2NewSourceFileSource(Path.of(args[0])),new L2FileCheckpointStore()).execute(request);
 }
}
