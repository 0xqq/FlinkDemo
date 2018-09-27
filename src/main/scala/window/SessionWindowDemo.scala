//package window
//
//import java.util.Properties
//
//import org.apache.flink.api.common.functions.ReduceFunction
//import org.apache.flink.api.common.state.ReducingStateDescriptor
//import org.apache.flink.api.common.typeutils.TypeSerializer
//import org.apache.flink.api.common.typeutils.base.LongSerializer
//import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
//import org.apache.flink.streaming.api.windowing.time.Time
//import org.apache.flink.streaming.api.windowing.windows.TimeWindow
//import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer09
//import org.apache.flink.api.scala._
//import org.apache.flink.streaming.api.windowing.triggers.{Trigger, TriggerResult}
//import org.apache.flink.streaming.api.windowing.triggers.Trigger.{OnMergeContext, TriggerContext}
//import org.apache.flink.streaming.util.serialization.SimpleStringSchema
//
//import scala.util.Properties
//
//object SessionWindowDemo extends App{
//  val env = StreamExecutionEnvironment.getExecutionEnvironment
//  val prop = new Properties()
//  prop.put("kafaka.broker.list","spark:9092")
//
//  val input = env.addSource(new FlinkKafkaConsumer09(
//    "address", //topic
//    new SimpleStringSchema,
//    prop //kafka properties
//  ))
//}
//
//case class Address(id:Int, name:String)
//
//class EarlyTriggeringTrigger(interval: Long) extends Trigger[Object, TimeWindow] {
//
//  //传入interval，作为参数传入此类的构造器，时间转换为毫秒
//  def every(interval: Time) = new EarlyTriggeringTrigger(interval.toMilliseconds)
//
//  //通过reduce函数维护一个Long类型的数据，此数据代表即将触发的时间戳
//  private type JavaLong = java.lang.Long
//  //这里取2个注册时间的最小值，因为首先注册的是窗口的maxTimestamp，也是最后一个要触发的时间
//  private val min: ReduceFunction[JavaLong] = new ReduceFunction[JavaLong] {
//    override def reduce(value1: JavaLong, value2: JavaLong): JavaLong = Math.min(value1, value2)
//  }
//
//  private val serializer: TypeSerializer[JavaLong] = LongSerializer.INSTANCE.asInstanceOf[TypeSerializer[JavaLong]]
//
//  private val stateDesc = new ReducingStateDescriptor[JavaLong]("fire-time", min, serializer)
//  //每个元素都会运行此方法
//  override def onElement(element: Object,
//                         timestamp: Long,
//                         window: TimeWindow,
//                         ctx: TriggerContext): TriggerResult =
//  //如果当前的watermark超过窗口的结束时间，则清除定时器内容，触发窗口计算
//    if (window.maxTimestamp <= ctx.getCurrentWatermark) {
//      clearTimerForState(ctx)
//      TriggerResult.FIRE
//    }
//    else {
//      //否则将窗口的结束时间注册给EventTime定时器
//      ctx.registerEventTimeTimer(window.maxTimestamp)
//      //获取当前状态中的时间戳
//      val fireTimestamp = ctx.getPartitionedState(stateDesc)
//      //如果第一次执行，则将元素的timestamp进行floor操作，取整后加上传入的实例变量interval，得到下一次触发时间并注册，添加到状态中
//      if (fireTimestamp.get == null) {
//        val start = timestamp - (timestamp % interval)
//        val nextFireTimestamp = start + interval
//        ctx.registerEventTimeTimer(nextFireTimestamp)
//        fireTimestamp.add(nextFireTimestamp)
//      }
//      //此时继续等待
//      TriggerResult.CONTINUE
//    }
//  //这里不基于processing time，因此永远不会基于processing time 触发
//  override def onProcessingTime(time: Long,
//                                window: TimeWindow,
//                                ctx: TriggerContext): TriggerResult = TriggerResult.CONTINUE
//  //之前注册的Event Time Timer定时器，当watermark超过注册的时间时，就会执行onEventTime方法
//  override def onEventTime(time: Long,
//                           window: TimeWindow,
//                           ctx: TriggerContext): TriggerResult = {
//    //如果注册的时间等于maxTimestamp时间，清空状态，并触发计算
//    if (time == window.maxTimestamp()) {
//      clearTimerForState(ctx)
//      TriggerResult.FIRE
//    } else {
//      //否则，获取状态中的值（maxTimestamp和nextFireTimestamp的最小值）
//      val fireTimestamp = ctx.getPartitionedState(stateDesc)
//      //如果状态中的值等于注册的时间，则删除此定时器时间戳，并注册下一个interval的时间，触发计算
//      //这里，前提条件是watermark超过了定时器中注册的时间，就会执行此方法，理论上状态中的fire time一定是等于注册的时间的
//      if (fireTimestamp.get == time) {
//        fireTimestamp.clear()
//        fireTimestamp.add(time + interval)
//        ctx.registerEventTimeTimer(time + interval)
//        TriggerResult.FIRE
//      } else {
//        //否则继续等待
//        TriggerResult.CONTINUE
//      }
//    }
//  }
//  //上下文中获取状态中的值，并从定时器中清除这个值
//  private def clearTimerForState(ctx: TriggerContext): Unit = {
//    val timestamp = ctx.getPartitionedState(stateDesc).get()
//    if (timestamp != null) {
//      ctx.deleteEventTimeTimer(timestamp)
//    }
//  }
//
//  //用于session window的merge，判断是否可以merge
//  override def canMerge: Boolean = true
//
//  override def onMerge(window: TimeWindow,
//                       ctx: OnMergeContext): TriggerResult = {
//    ctx.mergePartitionedState(stateDesc)
//    val nextFireTimestamp = ctx.getPartitionedState(stateDesc).get()
//    if (nextFireTimestamp != null) {
//      ctx.registerEventTimeTimer(nextFireTimestamp)
//    }
//    TriggerResult.CONTINUE
//  }
//  //删除定时器中已经触发的时间戳，并调用Trigger的clear方法
//  override def clear(window: TimeWindow,
//                     ctx: TriggerContext): Unit = {
//    ctx.deleteEventTimeTimer(window.maxTimestamp())
//    val fireTimestamp = ctx.getPartitionedState(stateDesc)
//    val timestamp = fireTimestamp.get
//    if (timestamp != null) {
//      ctx.deleteEventTimeTimer(timestamp)
//      fireTimestamp.clear()
//    }
//  }
//
//  override def toString: String = s"EarlyTriggeringTrigger($interval)"
//}