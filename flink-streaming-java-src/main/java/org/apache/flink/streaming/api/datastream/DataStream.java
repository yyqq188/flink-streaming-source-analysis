/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.datastream;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.Public;
import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.Partitioner;
import org.apache.flink.api.common.functions.RichFilterFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.io.OutputFormat;
import org.apache.flink.api.common.operators.Keys;
import org.apache.flink.api.common.operators.ResourceSpec;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicArrayTypeInfo;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.Utils;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.io.CsvOutputFormat;
import org.apache.flink.api.java.io.TextOutputFormat;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.typeutils.InputTypeConfigurable;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.core.fs.FileSystem.WriteMode;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.collector.selector.OutputSelector;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.functions.AssignerWithPunctuatedWatermarks;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.TimestampExtractor;
import org.apache.flink.streaming.api.functions.sink.OutputFormatSinkFunction;
import org.apache.flink.streaming.api.functions.sink.PrintSinkFunction;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.sink.SocketClientSink;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.ProcessOperator;
import org.apache.flink.streaming.api.operators.StreamFilter;
import org.apache.flink.streaming.api.operators.StreamFlatMap;
import org.apache.flink.streaming.api.operators.StreamMap;
import org.apache.flink.streaming.api.operators.StreamSink;
import org.apache.flink.streaming.api.transformations.OneInputTransformation;
import org.apache.flink.streaming.api.transformations.PartitionTransformation;
import org.apache.flink.streaming.api.transformations.StreamTransformation;
import org.apache.flink.streaming.api.transformations.UnionTransformation;
import org.apache.flink.streaming.api.windowing.assigners.GlobalWindows;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.SlidingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.WindowAssigner;
import org.apache.flink.streaming.api.windowing.evictors.CountEvictor;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.triggers.CountTrigger;
import org.apache.flink.streaming.api.windowing.triggers.PurgingTrigger;
import org.apache.flink.streaming.api.windowing.windows.GlobalWindow;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.api.windowing.windows.Window;
import org.apache.flink.streaming.runtime.operators.ExtractTimestampsOperator;
import org.apache.flink.streaming.runtime.operators.TimestampsAndPeriodicWatermarksOperator;
import org.apache.flink.streaming.runtime.operators.TimestampsAndPunctuatedWatermarksOperator;
import org.apache.flink.streaming.runtime.partitioner.BroadcastPartitioner;
import org.apache.flink.streaming.runtime.partitioner.CustomPartitionerWrapper;
import org.apache.flink.streaming.runtime.partitioner.ForwardPartitioner;
import org.apache.flink.streaming.runtime.partitioner.GlobalPartitioner;
import org.apache.flink.streaming.runtime.partitioner.RebalancePartitioner;
import org.apache.flink.streaming.runtime.partitioner.RescalePartitioner;
import org.apache.flink.streaming.runtime.partitioner.ShufflePartitioner;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.streaming.util.keys.KeySelectorUtil;
import org.apache.flink.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * A DataStream represents a stream of elements of the same type. A DataStream
 * can be transformed into another DataStream by applying a transformation as
 * for example:
 * <ul>
 * <li>{@link DataStream#map}
 * <li>{@link DataStream#filter}
 * </ul>
 *
 * @param <T> The type of the elements in this stream.
 */
/**
 * DataStream 指代 T 类型的一个数据流，一个 DataStream 可以通过 map/filter 等操作转换为其他数据流
 */
@Public
public class DataStream<T> {

	protected final StreamExecutionEnvironment environment;  // 当前的执行环境

	protected final StreamTransformation<T> transformation;  // transformation 算子

	/**
	 * Create a new {@link DataStream} in the given execution environment with
	 * partitioning set to forward by default.
	 *
	 * @param environment The StreamExecutionEnvironment
	 */
	/**
	 * 构造函数
	 * @param environment 执行环境
	 * @param transformation 算子
	 */
	public DataStream(StreamExecutionEnvironment environment, StreamTransformation<T> transformation) {
		this.environment = Preconditions.checkNotNull(environment, "Execution Environment must not be null.");
		this.transformation = Preconditions.checkNotNull(transformation, "Stream Transformation must not be null.");
	}

	/**
	 * Returns the ID of the {@link DataStream} in the current {@link StreamExecutionEnvironment}.
	 *
	 * @return ID of the DataStream
	 */
	/**
	 * 返回 DataStream 的 id，transformation 的 id 是用 static 方法生成的，用 transformation 的 id
	 * 指代 DataStream 的 id
	 */
	@Internal
	public int getId() {
		return transformation.getId();
	}

	/**
	 * Gets the parallelism for this operator.
	 *
	 * @return The parallelism set for this operator.
	 */
	/**
	 * 获取并行度
	 */
	public int getParallelism() {
		return transformation.getParallelism();
	}

	/**
	 * Gets the minimum resources for this operator.
	 *
	 * @return The minimum resources set for this operator.
	 */
	/**
	 * 获取最小资源
	 */
	@PublicEvolving
	public ResourceSpec getMinResources() {
		return transformation.getMinResources();
	}

	/**
	 * Gets the preferred resources for this operator.
	 *
	 * @return The preferred resources set for this operator.
	 */
	/**
	 * 获取最大资源
	 */
	@PublicEvolving
	public ResourceSpec getPreferredResources() {
		return transformation.getPreferredResources();
	}

	/**
	 * Gets the type of the stream.
	 *
	 * @return The type of the datastream.
	 */
	/**
	 * 获取 DataStream 的下游类型
	 */
	public TypeInformation<T> getType() {
		return transformation.getOutputType();
	}

	/**
	 * Invokes the {@link org.apache.flink.api.java.ClosureCleaner}
	 * on the given function if closure cleaning is enabled in the {@link ExecutionConfig}.
	 *
	 * @return The cleaned Function
	 */
	protected <F> F clean(F f) {
		return getExecutionEnvironment().clean(f);
	}

	/**
	 * Returns the {@link StreamExecutionEnvironment} that was used to create this
	 * {@link DataStream}.
	 *
	 * @return The Execution Environment
	 */
	public StreamExecutionEnvironment getExecutionEnvironment() {
		return environment;
	}

	/**
	 * 获取执行配置
	 */
	public ExecutionConfig getExecutionConfig() {
		return environment.getConfig();
	}

	/**
	 * Creates a new {@link DataStream} by merging {@link DataStream} outputs of
	 * the same type with each other. The DataStreams merged using this operator
	 * will be transformed simultaneously.
	 *
	 * @param streams
	 *            The DataStreams to union output with.
	 * @return The {@link DataStream}.
	 */
	/**
	 * 相同 OutputType DataStream 数据流合并
	 */
	@SafeVarargs
	public final DataStream<T> union(DataStream<T>... streams) {
		List<StreamTransformation<T>> unionedTransforms = new ArrayList<>();
		unionedTransforms.add(this.transformation);

		for (DataStream<T> newStream : streams) {
			if (!getType().equals(newStream.getType())) {
				throw new IllegalArgumentException("Cannot union streams of different types: "
						+ getType() + " and " + newStream.getType());
			}

			unionedTransforms.add(newStream.getTransformation());
		}
		return new DataStream<>(this.environment, new UnionTransformation<>(unionedTransforms));
	}

	/**
	 * Operator used for directing tuples to specific named outputs using an
	 * {@link org.apache.flink.streaming.api.collector.selector.OutputSelector}.
	 * Calling this method on an operator creates a new {@link SplitStream}.
	 *
	 * @param outputSelector
	 *            The user defined
	 *            {@link org.apache.flink.streaming.api.collector.selector.OutputSelector}
	 *            for directing the tuples.
	 * @return The {@link SplitStream}
	 * @deprecated Please use side output instead.
	 */
	/**
	 * 切分数据流
	 */
	@Deprecated
	public SplitStream<T> split(OutputSelector<T> outputSelector) {
		return new SplitStream<>(this, clean(outputSelector));
	}

	/**
	 * Creates a new {@link ConnectedStreams} by connecting
	 * {@link DataStream} outputs of (possible) different types with each other.
	 * The DataStreams connected using this operator can be used with
	 * CoFunctions to apply joint transformations.
	 *
	 * @param dataStream
	 *            The DataStream with which this stream will be connected.
	 * @return The {@link ConnectedStreams}.
	 */
	/**
	 * 合并两个不同 OutputType 的数据流，返回值可以应用 CoFunctions
	 */
	public <R> ConnectedStreams<T, R> connect(DataStream<R> dataStream) {
		return new ConnectedStreams<>(environment, this, dataStream);
	}

	/**
	 * Creates a new {@link BroadcastConnectedStream} by connecting the current
	 * {@link DataStream} or {@link KeyedStream} with a {@link BroadcastStream}.
	 *
	 * 连接当前的流和一个 BroadcastStream 生成一个 BroadcastConnectedStream
	 *
	 * <p>The latter can be created using the {@link #broadcast(MapStateDescriptor[])} method.
	 * 
	 * BroadcastStream 能够通过调用 broadcast(MapStateDescriptor[]) 方法得到
	 *
	 * <p>The resulting stream can be further processed using the {@code BroadcastConnectedStream.process(MyFunction)}
	 * method, where {@code MyFunction} can be either a
	 * {@link org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction KeyedBroadcastProcessFunction}
	 * or a {@link org.apache.flink.streaming.api.functions.co.BroadcastProcessFunction BroadcastProcessFunction}
	 * depending on the current stream being a {@link KeyedStream} or not.
	 *
	 * BroadcastConnectedStream 能够使用 process(MyFunction) 方法进行进一步的处理
	 * 其中 MyFunction 可以是 KeyedBroadcastProcessFunction 或 BroadcastProcessFunction
	 * 具体取决于当前流是否是 KeyedStream
	 * 
	 * @param broadcastStream The broadcast stream with the broadcast state to be connected with this stream.
	 * @return The {@link BroadcastConnectedStream}.
	 */
	@PublicEvolving
	public <R> BroadcastConnectedStream<T, R> connect(BroadcastStream<R> broadcastStream) {
		return new BroadcastConnectedStream<>(
				environment,
				this,
				Preconditions.checkNotNull(broadcastStream),
				broadcastStream.getBroadcastStateDescriptor());
	}

	/**
	 * It creates a new {@link KeyedStream} that uses the provided key for partitioning
	 * its operator states.
	 *
	 * @param key
	 *            The KeySelector to be used for extracting the key for partitioning
	 * @return The {@link DataStream} with partitioned state (i.e. KeyedStream)
	 */
	/**
	 * 使用 key 来对流进行分区，返回一个 KeyedStream
	 */
	public <K> KeyedStream<T, K> keyBy(KeySelector<T, K> key) {
		Preconditions.checkNotNull(key);
		return new KeyedStream<>(this, clean(key));
	}

	/**
	 * It creates a new {@link KeyedStream} that uses the provided key with explicit type information
	 * for partitioning its operator states.
	 *
	 * @param key The KeySelector to be used for extracting the key for partitioning.
	 * @param keyType The type information describing the key type.
	 * @return The {@link DataStream} with partitioned state (i.e. KeyedStream)
	 */
	/**
	 * 使用 key 以及明确的 keyType 来对流进行分区，返回一个 KeyedStream
	 */
	public <K> KeyedStream<T, K> keyBy(KeySelector<T, K> key, TypeInformation<K> keyType) {
		Preconditions.checkNotNull(key);
		Preconditions.checkNotNull(keyType);
		return new KeyedStream<>(this, clean(key), keyType);
	}

	/**
	 * Partitions the operator state of a {@link DataStream} by the given key positions.
	 *
	 * @param fields
	 *            The position of the fields on which the {@link DataStream}
	 *            will be grouped.
	 * @return The {@link DataStream} with partitioned state (i.e. KeyedStream)
	 */
	/**
	 * 使用 key 的位置来对流进行分区，返回一个 KeyedStream
	 */
	public KeyedStream<T, Tuple> keyBy(int... fields) {
		if (getType() instanceof BasicArrayTypeInfo || getType() instanceof PrimitiveArrayTypeInfo) {
			return keyBy(KeySelectorUtil.getSelectorForArray(fields, getType()));
		} else {
			return keyBy(new Keys.ExpressionKeys<>(fields, getType()));
		}
	}

	/**
	 * Partitions the operator state of a {@link DataStream} using field expressions.
	 * A field expression is either the name of a public field or a getter method with parentheses
	 * of the {@link DataStream}'s underlying type. A dot can be used to drill
	 * down into objects, as in {@code "field1.getInnerField2()" }.
	 *
	 * @param fields
	 *            One or more field expressions on which the state of the {@link DataStream} operators will be
	 *            partitioned.
	 * @return The {@link DataStream} with partitioned state (i.e. KeyedStream)
	 **/
	public KeyedStream<T, Tuple> keyBy(String... fields) {
		return keyBy(new Keys.ExpressionKeys<>(fields, getType()));
	}

	private KeyedStream<T, Tuple> keyBy(Keys<T> keys) {
		return new KeyedStream<>(this, clean(KeySelectorUtil.getSelectorForKeys(keys,
				getType(), getExecutionConfig())));
	}

	/**
	 * Partitions a tuple DataStream on the specified key fields using a custom partitioner.
	 * This method takes the key position to partition on, and a partitioner that accepts the key type.
	 *
	 * <p>Note: This method works only on single field keys.
	 *
	 * @param partitioner The partitioner to assign partitions to keys.
	 * @param field The field index on which the DataStream is partitioned.
	 * @return The partitioned DataStream.
	 */
	public <K> DataStream<T> partitionCustom(Partitioner<K> partitioner, int field) {
		Keys.ExpressionKeys<T> outExpressionKeys = new Keys.ExpressionKeys<>(new int[]{field}, getType());
		return partitionCustom(partitioner, outExpressionKeys);
	}

	/**
	 * Partitions a POJO DataStream on the specified key fields using a custom partitioner.
	 * This method takes the key expression to partition on, and a partitioner that accepts the key type.
	 *
	 * <p>Note: This method works only on single field keys.
	 *
	 * @param partitioner The partitioner to assign partitions to keys.
	 * @param field The expression for the field on which the DataStream is partitioned.
	 * @return The partitioned DataStream.
	 */
	public <K> DataStream<T> partitionCustom(Partitioner<K> partitioner, String field) {
		Keys.ExpressionKeys<T> outExpressionKeys = new Keys.ExpressionKeys<>(new String[]{field}, getType());
		return partitionCustom(partitioner, outExpressionKeys);
	}


	/**
	 * Partitions a DataStream on the key returned by the selector, using a custom partitioner.
	 * This method takes the key selector to get the key to partition on, and a partitioner that
	 * accepts the key type.
	 *
	 * <p>Note: This method works only on single field keys, i.e. the selector cannot return tuples
	 * of fields.
	 *
	 * @param partitioner
	 * 		The partitioner to assign partitions to keys.
	 * @param keySelector
	 * 		The KeySelector with which the DataStream is partitioned.
	 * @return The partitioned DataStream.
	 * @see KeySelector
	 */
	/**
	 * 自定义 partition
	 */
	public <K> DataStream<T> partitionCustom(Partitioner<K> partitioner, KeySelector<T, K> keySelector) {
		return setConnectionType(new CustomPartitionerWrapper<>(clean(partitioner),
				clean(keySelector)));
	}

	//	private helper method for custom partitioning
	private <K> DataStream<T> partitionCustom(Partitioner<K> partitioner, Keys<T> keys) {
		KeySelector<T, K> keySelector = KeySelectorUtil.getSelectorForOneKey(keys, partitioner, getType(), getExecutionConfig());

		return setConnectionType(
				new CustomPartitionerWrapper<>(
						clean(partitioner),
						clean(keySelector)));
	}

	/**
	 * Sets the partitioning of the {@link DataStream} so that the output elements
	 * are broadcasted to every parallel instance of the next operation.
	 *
	 * @return The DataStream with broadcast partitioning set.
	 */
	/**
	 * 给 DataStream 设置 broadcast partitioner，输出元素会被广播到
	 * 下一个操作的全部并行实例上
	 */
	public DataStream<T> broadcast() {
		return setConnectionType(new BroadcastPartitioner<T>());
	}

	/**
	 * Sets the partitioning of the {@link DataStream} so that the output elements
	 * are broadcasted to every parallel instance of the next operation. In addition,
	 * it implicitly as many {@link org.apache.flink.api.common.state.BroadcastState broadcast states}
	 * as the specified descriptors which can be used to store the element of the stream.
	 *
	 * @param broadcastStateDescriptors the descriptors of the broadcast states to create.
	 * @return A {@link BroadcastStream} which can be used in the {@link #connect(BroadcastStream)} to
	 * create a {@link BroadcastConnectedStream} for further processing of the elements.
	 */
	/**
	 * 设置 DataStream 的分区方式为广播分区，这样输出元素会被广播到下游的每一个并行实例中
	 * 另外，它包含 broadcastStateDescriptors，能够用来存储流的元素
	 */
	@PublicEvolving
	public BroadcastStream<T> broadcast(final MapStateDescriptor<?, ?>... broadcastStateDescriptors) {
		Preconditions.checkNotNull(broadcastStateDescriptors);
		final DataStream<T> broadcastStream = setConnectionType(new BroadcastPartitioner<>());
		return new BroadcastStream<>(environment, broadcastStream, broadcastStateDescriptors);
	}

	/**
	 * Sets the partitioning of the {@link DataStream} so that the output elements
	 * are shuffled uniformly randomly to the next operation.
	 *
	 * @return The DataStream with shuffle partitioning set.
	 */
	/**
	 * 给 DataStream 设置 shuffle partitioner，这样输出元素会被随机的打散到下一个操作中
	 */
	@PublicEvolving
	public DataStream<T> shuffle() {
		return setConnectionType(new ShufflePartitioner<T>());
	}

	/**
	 * Sets the partitioning of the {@link DataStream} so that the output elements
	 * are forwarded to the local subtask of the next operation.
	 *
	 * @return The DataStream with forward partitioning set.
	 */
	/**
	 * 给 DataStream 设置 forward partitioner，这样输出元素直接送到本地 subtask 的下一个操作中
	 */
	public DataStream<T> forward() {
		return setConnectionType(new ForwardPartitioner<T>());
	}

	/**
	 * Sets the partitioning of the {@link DataStream} so that the output elements
	 * are distributed evenly to instances of the next operation in a round-robin
	 * fashion.
	 *
	 * @return The DataStream with rebalance partitioning set.
	 */
	/**
	 * 给 DataStream 设置 rebalance partitioner，这样输出元素按照轮询的方式送到
	 * 下一个操作中去
	 */
	public DataStream<T> rebalance() {
		return setConnectionType(new RebalancePartitioner<T>());
	}

	/**
	 * Sets the partitioning of the {@link DataStream} so that the output elements
	 * are distributed evenly to a subset of instances of the next operation in a round-robin
	 * fashion.
	 *
	 * <p>The subset of downstream operations to which the upstream operation sends
	 * elements depends on the degree of parallelism of both the upstream and downstream operation.
	 * For example, if the upstream operation has parallelism 2 and the downstream operation
	 * has parallelism 4, then one upstream operation would distribute elements to two
	 * downstream operations while the other upstream operation would distribute to the other
	 * two downstream operations. If, on the other hand, the downstream operation has parallelism
	 * 2 while the upstream operation has parallelism 4 then two upstream operations will
	 * distribute to one downstream operation while the other two upstream operations will
	 * distribute to the other downstream operations.
	 *
	 * <p>In cases where the different parallelisms are not multiples of each other one or several
	 * downstream operations will have a differing number of inputs from upstream operations.
	 *
	 * @return The DataStream with rescale partitioning set.
	 */
	/**
	 * 给 DataStream 设置 rescale partitioner，这样输出元素按照轮询的方式送到下一个操作中去
	 *
	 * rescale 和 rebalance 有些类似，但是不是全局的，通过轮询调度将元素从上游的 task 一个子集
	 * 发送到下游 task 的一个子集
	 * 
	 * rescale 只在 TaskManager 内，rebalance 会在跨 TaskManager 分配
	 * 有网络传输代价，但是数据倾斜非常有用
	 */
	@PublicEvolving
	public DataStream<T> rescale() {
		return setConnectionType(new RescalePartitioner<T>());
	}

	/**
	 * Sets the partitioning of the {@link DataStream} so that the output values
	 * all go to the first instance of the next processing operator. Use this
	 * setting with care since it might cause a serious performance bottleneck
	 * in the application.
	 *
	 * @return The DataStream with shuffle partitioning set.
	 */
	/**
	 * 给 DataStream 设置 global partitioner，输出元素会被全部送到下一个
	 * 操作的第一个实例里面去，使用这个方法需要非常谨慎，因为很可能会造成
	 * 性能瓶颈
	 */
	@PublicEvolving
	public DataStream<T> global() {
		return setConnectionType(new GlobalPartitioner<T>());
	}

	/**
	 * Initiates an iterative part of the program that feeds back data streams.
	 * The iterative part needs to be closed by calling
	 * {@link IterativeStream#closeWith(DataStream)}. The transformation of
	 * this IterativeStream will be the iteration head. The data stream
	 * given to the {@link IterativeStream#closeWith(DataStream)} method is
	 * the data stream that will be fed back and used as the input for the
	 * iteration head. The user can also use different feedback type than the
	 * input of the iteration and treat the input and feedback streams as a
	 * {@link ConnectedStreams} be calling
	 * {@link IterativeStream#withFeedbackType(TypeInformation)}
	 *
	 * 启动程序中的迭代部分，这部分将反馈数据流。迭代部分需要调用 IterativeStream 的
	 * closeWith(DataStream) 方法来关闭。IterativeStream 的算子是迭代的头部。
	 * closeWith 的参数是将被反馈并用作迭代头输入的数据流。
	 * 通过调用 withFeedbackType 用户还可以使用不同于迭代输入的反馈类型，并将输入流和反馈流视为正在调用的连接流
	 *
	 * <p>A common usage pattern for streaming iterations is to use output
	 * splitting to send a part of the closing data stream to the head. Refer to
	 * {@link #split(OutputSelector)} for more information.
	 *
	 * 流迭代的一个常见使用模式是使用输出拆分将关闭的数据流的一部分发送到头部
	 *
	 * <p>The iteration edge will be partitioned the same way as the first input of
	 * the iteration head unless it is changed in the
	 * {@link IterativeStream#closeWith(DataStream)} call.
	 *
	 * 迭代边将以迭代头的第一个输入相同的方式进行分区
	 * 除非在 closewith（datastream）调用中对其进行了更改。
	 *
	 * <p>By default a DataStream with iteration will never terminate, but the user
	 * can use the maxWaitTime parameter to set a max waiting time for the
	 * iteration head. If no data received in the set time, the stream
	 * terminates.
	 *
	 * 默认情况下，具有迭代的数据流永远不会终止，但用户可以使用 maxWaitTime 参数
	 * 为迭代头设置最大等待时间。如果在这段时间内没有数据到来，数据流停止
	 *
	 * @return The iterative data stream created.
	 */
	@PublicEvolving
	public IterativeStream<T> iterate() {
		return new IterativeStream<>(this, 0);
	}

	/**
	 * Initiates an iterative part of the program that feeds back data streams.
	 * The iterative part needs to be closed by calling
	 * {@link IterativeStream#closeWith(DataStream)}. The transformation of
	 * this IterativeStream will be the iteration head. The data stream
	 * given to the {@link IterativeStream#closeWith(DataStream)} method is
	 * the data stream that will be fed back and used as the input for the
	 * iteration head. The user can also use different feedback type than the
	 * input of the iteration and treat the input and feedback streams as a
	 * {@link ConnectedStreams} be calling
	 * {@link IterativeStream#withFeedbackType(TypeInformation)}
	 *
	 * <p>A common usage pattern for streaming iterations is to use output
	 * splitting to send a part of the closing data stream to the head. Refer to
	 * {@link #split(OutputSelector)} for more information.
	 *
	 * <p>The iteration edge will be partitioned the same way as the first input of
	 * the iteration head unless it is changed in the
	 * {@link IterativeStream#closeWith(DataStream)} call.
	 *
	 * <p>By default a DataStream with iteration will never terminate, but the user
	 * can use the maxWaitTime parameter to set a max waiting time for the
	 * iteration head. If no data received in the set time, the stream
	 * terminates.
	 *
	 * @param maxWaitTimeMillis
	 *            Number of milliseconds to wait between inputs before shutting
	 *            down
	 *
	 * @return The iterative data stream created.
	 */
	@PublicEvolving
	public IterativeStream<T> iterate(long maxWaitTimeMillis) {
		return new IterativeStream<>(this, maxWaitTimeMillis);
	}

	/**
	 * Applies a Map transformation on a {@link DataStream}. The transformation
	 * calls a {@link MapFunction} for each element of the DataStream. Each
	 * MapFunction call returns exactly one element. The user can also extend
	 * {@link RichMapFunction} to gain access to other features provided by the
	 * {@link org.apache.flink.api.common.functions.RichFunction} interface.
	 *
	 * @param mapper
	 *            The MapFunction that is called for each element of the
	 *            DataStream.
	 * @param <R>
	 *            output type
	 * @return The transformed {@link DataStream}.
	 */
	/**
	 * 在 DataStream 上应用一个 map transformation
	 * 这个 transformation 对数据流中的每个元素调用 MapFunction
	 * 每一个 MapFunction 调用返回仅仅一个元素
	 */
	public <R> SingleOutputStreamOperator<R> map(MapFunction<T, R> mapper) {

		TypeInformation<R> outType = TypeExtractor.getMapReturnTypes(clean(mapper), getType(),
				Utils.getCallLocationName(), true);

		return transform("Map", outType, new StreamMap<>(clean(mapper)));
	}

	/**
	 * Applies a FlatMap transformation on a {@link DataStream}. The
	 * transformation calls a {@link FlatMapFunction} for each element of the
	 * DataStream. Each FlatMapFunction call can return any number of elements
	 * including none. The user can also extend {@link RichFlatMapFunction} to
	 * gain access to other features provided by the
	 * {@link org.apache.flink.api.common.functions.RichFunction} interface.
	 *
	 * 在 DataStream 上应用一个 FlatMap transformation
	 * 这个 transformation 对数据流中的每一个元素执行 FlatMapFunction
	 * 每一个 FlatMapFunction 可以返回任意数量的元素（可能没有）
	 *
	 * @param flatMapper
	 *            The FlatMapFunction that is called for each element of the
	 *            DataStream
	 *
	 * @param <R>
	 *            output type
	 * @return The transformed {@link DataStream}.
	 */
	public <R> SingleOutputStreamOperator<R> flatMap(FlatMapFunction<T, R> flatMapper) {

		TypeInformation<R> outType = TypeExtractor.getFlatMapReturnTypes(clean(flatMapper),
				getType(), Utils.getCallLocationName(), true);

		return transform("Flat Map", outType, new StreamFlatMap<>(clean(flatMapper)));

	}

	/**
	 * Applies the given {@link ProcessFunction} on the input stream, thereby
	 * creating a transformed output stream.
	 *
	 * <p>The function will be called for every element in the input streams and can produce zero
	 * or more output elements.
	 *
	 * @param processFunction The {@link ProcessFunction} that is called for each element
	 *                      in the stream.
	 *
	 * @param <R> The type of elements emitted by the {@code ProcessFunction}.
	 *
	 * @return The transformed {@link DataStream}.
	 */
	@PublicEvolving
	public <R> SingleOutputStreamOperator<R> process(ProcessFunction<T, R> processFunction) {

		TypeInformation<R> outType = TypeExtractor.getUnaryOperatorReturnType(
			processFunction,
			ProcessFunction.class,
			0,
			1,
			TypeExtractor.NO_INDEX,
			getType(),
			Utils.getCallLocationName(),
			true);

		return process(processFunction, outType);
	}

	/**
	 * Applies the given {@link ProcessFunction} on the input stream, thereby
	 * creating a transformed output stream.
	 *
	 * <p>The function will be called for every element in the input streams and can produce zero
	 * or more output elements.
	 *
	 * @param processFunction The {@link ProcessFunction} that is called for each element
	 *                      in the stream.
	 * @param outputType {@link TypeInformation} for the result type of the function.
	 *
	 * @param <R> The type of elements emitted by the {@code ProcessFunction}.
	 *
	 * @return The transformed {@link DataStream}.
	 */
	/**
	 * process 是较为底层处理数据的方法，和 flatMap 差不多，但是能够访问定时器（只有 KeyedStream 能够访问定时器）
	 */
	@Internal
	public <R> SingleOutputStreamOperator<R> process(
			ProcessFunction<T, R> processFunction,
			TypeInformation<R> outputType) {

		ProcessOperator<T, R> operator = new ProcessOperator<>(clean(processFunction));

		return transform("Process", outputType, operator);
	}

	/**
	 * Applies a Filter transformation on a {@link DataStream}. The
	 * transformation calls a {@link FilterFunction} for each element of the
	 * DataStream and retains only those element for which the function returns
	 * true. Elements for which the function returns false are filtered. The
	 * user can also extend {@link RichFilterFunction} to gain access to other
	 * features provided by the
	 * {@link org.apache.flink.api.common.functions.RichFunction} interface.
	 *
	 * 在 DataStream 上应用一个 filter transformation(OneInputTransformation)
	 * 这个 transformation 对数据流中的每个元素调用 FilterFunction
	 * 每一个 FilterFunction 要么返回当前元素，要么不返回任何元素
	 *
	 * @param filter
	 *            The FilterFunction that is called for each element of the
	 *            DataStream.
	 * @return The filtered DataStream.
	 */
	public SingleOutputStreamOperator<T> filter(FilterFunction<T> filter) {
		return transform("Filter", getType(), new StreamFilter<>(clean(filter)));

	}

	/**
	 * Initiates a Project transformation on a {@link Tuple} {@link DataStream}.<br>
	 * <b>Note: Only Tuple DataStreams can be projected.</b>
     *
	 * 对数据流执行映射操作，只有 Tuple 数据流能够被映射
	 * DataStream<Tuple4<Integer, Double, String, String>> in = // [...] 
	 * DataStream<Tuple2<String, String>> out = in.project(3,2);
	 *
	 * <p>The transformation projects each Tuple of the DataSet onto a (sub)set of
	 * fields.
	 *
	 * @param fieldIndexes
	 *            The field indexes of the input tuples that are retained. The
	 *            order of fields in the output tuple corresponds to the order
	 *            of field indexes.
	 * @return The projected DataStream
	 *
	 * @see Tuple
	 * @see DataStream
	 */
	@PublicEvolving
	public <R extends Tuple> SingleOutputStreamOperator<R> project(int... fieldIndexes) {
		return new StreamProjection<>(this, fieldIndexes).projectTupleX();
	}

	/**
	 * Creates a join operation. See {@link CoGroupedStreams} for an example of how the keys
	 * and window can be specified.
	 */
	/**
	 * 创建一个 join 操作
	 */
	public <T2> CoGroupedStreams<T, T2> coGroup(DataStream<T2> otherStream) {
		return new CoGroupedStreams<>(this, otherStream);
	}

	/**
	 * Creates a join operation. See {@link JoinedStreams} for an example of how the keys
	 * and window can be specified.
	 */
	/**
	 * 创建一个 join 操作
	 */
	public <T2> JoinedStreams<T, T2> join(DataStream<T2> otherStream) {
		return new JoinedStreams<>(this, otherStream);
	}

	/**
	 * Windows this {@code DataStream} into tumbling time windows.
	 *
	 * <p>This is a shortcut for either {@code .window(TumblingEventTimeWindows.of(size))} or
	 * {@code .window(TumblingProcessingTimeWindows.of(size))} depending on the time characteristic
	 * set using
	 *
	 * <p>Note: This operation is inherently non-parallel since all elements have to pass through
	 * the same operator instance.
	 *
	 * {@link org.apache.flink.streaming.api.environment.StreamExecutionEnvironment#setStreamTimeCharacteristic(org.apache.flink.streaming.api.TimeCharacteristic)}
	 *
	 * @param size The size of the window.
	 */
	/**
	 * 窗口化 DataStream 为翻转时间窗口
	 *
	 * 需要注意的是，这个操作是非并行的，因为所有的元素都需要通过相同的操作符实例
	 */
	public AllWindowedStream<T, TimeWindow> timeWindowAll(Time size) {
		if (environment.getStreamTimeCharacteristic() == TimeCharacteristic.ProcessingTime) {
			return windowAll(TumblingProcessingTimeWindows.of(size));
		} else {
			return windowAll(TumblingEventTimeWindows.of(size));
		}
	}

	/**
	 * Windows this {@code DataStream} into sliding time windows.
	 *
	 * <p>This is a shortcut for either {@code .window(SlidingEventTimeWindows.of(size, slide))} or
	 * {@code .window(SlidingProcessingTimeWindows.of(size, slide))} depending on the time characteristic
	 * set using
	 * {@link org.apache.flink.streaming.api.environment.StreamExecutionEnvironment#setStreamTimeCharacteristic(org.apache.flink.streaming.api.TimeCharacteristic)}
	 *
	 * <p>Note: This operation is inherently non-parallel since all elements have to pass through
	 * the same operator instance.
	 *
	 * @param size The size of the window.
	 */
	/**
	 * 窗口化 DataStream 为滑动时间窗口
	 */
	public AllWindowedStream<T, TimeWindow> timeWindowAll(Time size, Time slide) {
		if (environment.getStreamTimeCharacteristic() == TimeCharacteristic.ProcessingTime) {
			return windowAll(SlidingProcessingTimeWindows.of(size, slide));
		} else {
			return windowAll(SlidingEventTimeWindows.of(size, slide));
		}
	}

	/**
	 * Windows this {@code DataStream} into tumbling count windows.
	 *
	 * <p>Note: This operation is inherently non-parallel since all elements have to pass through
	 * the same operator instance.
	 *
	 * @param size The size of the windows in number of elements.
	 */
	/**
	 * 窗口化 DataStream 为翻转计数窗口
	 */
	public AllWindowedStream<T, GlobalWindow> countWindowAll(long size) {
		return windowAll(GlobalWindows.create()).trigger(PurgingTrigger.of(CountTrigger.of(size)));
	}

	/**
	 * Windows this {@code DataStream} into sliding count windows.
	 *
	 * <p>Note: This operation is inherently non-parallel since all elements have to pass through
	 * the same operator instance.
	 *
	 * @param size The size of the windows in number of elements.
	 * @param slide The slide interval in number of elements.
	 */
	/**
	 * 窗口化 DataStream 为滑动计数窗口
	 */
	public AllWindowedStream<T, GlobalWindow> countWindowAll(long size, long slide) {
		return windowAll(GlobalWindows.create())
				.evictor(CountEvictor.of(size))
				.trigger(CountTrigger.of(slide));
	}

	/**
	 * Windows this data stream to a {@code AllWindowedStream}, which evaluates windows
	 * over a non key grouped stream. Elements are put into windows by a
	 * {@link org.apache.flink.streaming.api.windowing.assigners.WindowAssigner}. The grouping of
	 * elements is done by window.
	 *
	 * <p>A {@link org.apache.flink.streaming.api.windowing.triggers.Trigger} can be defined to specify
	 * when windows are evaluated. However, {@code WindowAssigners} have a default {@code Trigger}
	 * that is used if a {@code Trigger} is not specified.
	 *
	 * <p>Note: This operation is inherently non-parallel since all elements have to pass through
	 * the same operator instance.
	 *
	 * @param assigner The {@code WindowAssigner} that assigns elements to windows.
	 * @return The trigger windows data stream.
	 */
	/**
	 * 窗口化数据流为 AllWindowedStream
	 * 元素通过 WindowAssigner 被放入窗口，元素的分组由窗口完成
	 *
	 * 一个 Trigger 能够用来定义窗口什么时候执行
	 * WindowAssigner 有一个默认的触发器，如果 Trigger 没有被定义，使用默认的触发器
	 * 这个操作是非并行的，因为所有的元素都需要经过同一个操作符实例
	 */
	@PublicEvolving
	public <W extends Window> AllWindowedStream<T, W> windowAll(WindowAssigner<? super T, W> assigner) {
		return new AllWindowedStream<>(this, assigner);
	}

	// ------------------------------------------------------------------------
	//  Timestamps and watermarks
	// ------------------------------------------------------------------------

	/**
	 * Extracts a timestamp from an element and assigns it as the internal timestamp of that element.
	 * The internal timestamps are, for example, used to to event-time window operations.
	 *
	 * 从元素中提取时间戳并将其作为元素的内部时间戳
	 *
	 * <p>If you know that the timestamps are strictly increasing you can use an
	 * {@link AscendingTimestampExtractor}. Otherwise,
	 * you should provide a {@link TimestampExtractor} that also implements
	 * {@link TimestampExtractor#getCurrentWatermark()} to keep track of watermarks.
	 *
	 * 如果你知道 ts 是严格递增的，你能使用一个 AscendingTimestampExtractor
	 * 否则，你应该提供一个 TimestampExtractor 来跟踪 watermark
	 *
	 * @param extractor The TimestampExtractor that is called for each element of the DataStream.
	 *
	 * @deprecated Please use {@link #assignTimestampsAndWatermarks(AssignerWithPeriodicWatermarks)}
	 *             of {@link #assignTimestampsAndWatermarks(AssignerWithPunctuatedWatermarks)}
	 *             instead.
	 * @see #assignTimestampsAndWatermarks(AssignerWithPeriodicWatermarks)
	 * @see #assignTimestampsAndWatermarks(AssignerWithPunctuatedWatermarks)
	 */
	@Deprecated
	public SingleOutputStreamOperator<T> assignTimestamps(TimestampExtractor<T> extractor) {
		// match parallelism to input, otherwise dop=1 sources could lead to some strange
		// behaviour: the watermark will creep along very slowly because the elements
		// from the source go to each extraction operator round robin.
		int inputParallelism = getTransformation().getParallelism();
		ExtractTimestampsOperator<T> operator = new ExtractTimestampsOperator<>(clean(extractor));
		return transform("ExtractTimestamps", getTransformation().getOutputType(), operator)
				.setParallelism(inputParallelism);
	}

	/**
	 * Assigns timestamps to the elements in the data stream and periodically creates
	 * watermarks to signal event time progress.
	 *
	 * 为数据流中的元素指定 ts 并且定期的创建 watermarks 来标识事件时间进度
	 *
	 * <p>This method creates watermarks periodically (for example every second), based
	 * on the watermarks indicated by the given watermark generator. Even when no new elements
	 * in the stream arrive, the given watermark generator will be periodically checked for
	 * new watermarks. The interval in which watermarks are generated is defined in
	 * {@link ExecutionConfig#setAutoWatermarkInterval(long)}.
	 *
	 * 这个方法依据给定的水印生成器周期性的创建 watermark（例如每秒一次）
	 * 即使流中没有新的元素到达，给定的水印生成器也将定期检查新的水印
	 * watermarks 生成的间隔在 ExecutionConfig#setAutoWatermarkInterval(long) 中被定义
	 *
	 * <p>Use this method for the common cases, where some characteristic over all elements
	 * should generate the watermarks, or where watermarks are simply trailing behind the
	 * wall clock time by a certain amount.
	 *
	 * 对于常见情况，请使用此方法，其中所有元素的某些特征应生成水印，或者水印仅仅在时间后面固定的数值
	 *
	 * <p>For the second case and when the watermarks are required to lag behind the maximum
	 * timestamp seen so far in the elements of the stream by a fixed amount of time, and this
	 * amount is known in advance, use the
	 * {@link BoundedOutOfOrdernessTimestampExtractor}.
	 *
	 * 对于第二种情况，并且当要求水印滞后于目前为止在流的元素中看到的最大时间戳固定的时间量
	 * 并且该量是预先知道的，使用 BoundedOutOfOrdernessTimestampExtractor
	 *
	 * <p>For cases where watermarks should be created in an irregular fashion, for example
	 * based on certain markers that some element carry, use the
	 * {@link AssignerWithPunctuatedWatermarks}.
	 *
	 * 对于以不规则方式创建水印的情况，例如基于某些元素携带的某些标记，请使用 AssignerWithPunctuatedWatermarks
	 *
	 * @param timestampAndWatermarkAssigner The implementation of the timestamp assigner and
	 *                                      watermark generator.
	 * @return The stream after the transformation, with assigned timestamps and watermarks.
	 *
	 * @see AssignerWithPeriodicWatermarks
	 * @see AssignerWithPunctuatedWatermarks
	 * @see #assignTimestampsAndWatermarks(AssignerWithPunctuatedWatermarks)
	 *
	 * 网上的例子 https://blog.csdn.net/u013560925/article/details/82285631
	 */
	public SingleOutputStreamOperator<T> assignTimestampsAndWatermarks(
			AssignerWithPeriodicWatermarks<T> timestampAndWatermarkAssigner) {

		// match parallelism to input, otherwise dop=1 sources could lead to some strange
		// behaviour: the watermark will creep along very slowly because the elements
		// from the source go to each extraction operator round robin.
		/**
		 * 将并行性与输入相匹配，否则 dop = 1 源可能会导致一些奇怪的行为：
		 * 水印将会非常缓慢地蠕变，因为源中的元素会转到每个提取运算符循环
		 */
		final int inputParallelism = getTransformation().getParallelism();
		final AssignerWithPeriodicWatermarks<T> cleanedAssigner = clean(timestampAndWatermarkAssigner);

		TimestampsAndPeriodicWatermarksOperator<T> operator =
				new TimestampsAndPeriodicWatermarksOperator<>(cleanedAssigner);

		return transform("Timestamps/Watermarks", getTransformation().getOutputType(), operator)
				.setParallelism(inputParallelism);
	}

	/**
	 * Assigns timestamps to the elements in the data stream and creates watermarks to
	 * signal event time progress based on the elements themselves.
	 *
	 * 为数据流中的元素分配时间戳，并根据元素本身创建水印以指示事件时间进度
	 * 
	 * <p>This method creates watermarks based purely on stream elements. For each element
	 * that is handled via {@link AssignerWithPunctuatedWatermarks#extractTimestamp(Object, long)},
	 * the {@link AssignerWithPunctuatedWatermarks#checkAndGetNextWatermark(Object, long)}
	 * method is called, and a new watermark is emitted, if the returned watermark value is
	 * non-negative and greater than the previous watermark.
	 *
	 * 此方法仅基于流元素创建水印，对于通过 extractTimestamp（Object，long）处理的每个元素，
	 * 调用 checkAndGetNextWatermark（Object，long）方法
	 * 如果返回的水印值为非负值并且大于之前的水印，则会发出新的水印
	 *
	 * <p>This method is useful when the data stream embeds watermark elements, or certain elements
	 * carry a marker that can be used to determine the current event time watermark.
	 * This operation gives the programmer full control over the watermark generation. Users
	 * should be aware that too aggressive watermark generation (i.e., generating hundreds of
	 * watermarks every second) can cost some performance.
	 *
	 * 当数据流嵌入水印元素时，该方法很有用，或者某些元素带有可用于确定当前事件时间水印的标记
	 * 此操作使程序员可以完全控制水印生成。用户应该意识到过于激进的水印生成（即，每秒产生数百个水印）
	 * 可能会花费一些性能
	 * 
	 * <p>For cases where watermarks should be created in a regular fashion, for example
	 * every x milliseconds, use the {@link AssignerWithPeriodicWatermarks}.
	 *
	 * 对于应该以常规方式创建水印的情况，例如每x毫秒，使用 AssignerWithPeriodicWatermarks
	 *
	 * @param timestampAndWatermarkAssigner The implementation of the timestamp assigner and
	 *                                      watermark generator.
	 * @return The stream after the transformation, with assigned timestamps and watermarks.
	 *
	 * @see AssignerWithPunctuatedWatermarks
	 * @see AssignerWithPeriodicWatermarks
	 * @see #assignTimestampsAndWatermarks(AssignerWithPeriodicWatermarks)
	 */
	public SingleOutputStreamOperator<T> assignTimestampsAndWatermarks(
			AssignerWithPunctuatedWatermarks<T> timestampAndWatermarkAssigner) {

		// match parallelism to input, otherwise dop=1 sources could lead to some strange
		// behaviour: the watermark will creep along very slowly because the elements
		// from the source go to each extraction operator round robin.
		final int inputParallelism = getTransformation().getParallelism();
		final AssignerWithPunctuatedWatermarks<T> cleanedAssigner = clean(timestampAndWatermarkAssigner);

		TimestampsAndPunctuatedWatermarksOperator<T> operator =
				new TimestampsAndPunctuatedWatermarksOperator<>(cleanedAssigner);

		return transform("Timestamps/Watermarks", getTransformation().getOutputType(), operator)
				.setParallelism(inputParallelism);
	}

	// ------------------------------------------------------------------------
	//  Data sinks
	// ------------------------------------------------------------------------
	// 数据下沉
	/**
	 * Writes a DataStream to the standard output stream (stdout).
	 *
	 * <p>For each element of the DataStream the result of {@link Object#toString()} is written.
	 *
	 * <p>NOTE: This will print to stdout on the machine where the code is executed, i.e. the Flink
	 * worker.
	 *
	 * @return The closed DataStream.
	 */
	/**
	 * 将 DataStream 写入标准输出流（stdout）
	 * 对于数据流中的每一个元素，Object.toString() 的结果被 sink
	 * 注意：这将在执行代码的机器上打印到 stdout
	 */
	@PublicEvolving
	public DataStreamSink<T> print() {
		PrintSinkFunction<T> printFunction = new PrintSinkFunction<>();
		return addSink(printFunction).name("Print to Std. Out");
	}

	/**
	 * Writes a DataStream to the standard output stream (stderr).
	 *
	 * <p>For each element of the DataStream the result of {@link Object#toString()} is written.
	 *
	 * <p>NOTE: This will print to stderr on the machine where the code is executed, i.e. the Flink
	 * worker.
	 *
	 * @return The closed DataStream.
	 */
	/**
	 * 将 DataStream 写入标准输出流（stderr）
	 * 对于数据流中的每一个元素，Object.toString() 的结果被 sink
	 * 注意：这将在执行代码的机器上打印到 stderr
	 */
	@PublicEvolving
	public DataStreamSink<T> printToErr() {
		PrintSinkFunction<T> printFunction = new PrintSinkFunction<>(true);
		return addSink(printFunction).name("Print to Std. Err");
	}

	/**
	 * Writes a DataStream to the standard output stream (stdout).
	 *
	 * <p>For each element of the DataStream the result of {@link Object#toString()} is written.
	 *
	 * <p>NOTE: This will print to stdout on the machine where the code is executed, i.e. the Flink
	 * worker.
	 *
	 * @param sinkIdentifier The string to prefix the output with.
	 * @return The closed DataStream.
	 */
	@PublicEvolving
	public DataStreamSink<T> print(String sinkIdentifier) {
		PrintSinkFunction<T> printFunction = new PrintSinkFunction<>(sinkIdentifier, false);
		return addSink(printFunction).name("Print to Std. Out");
	}

	/**
	 * Writes a DataStream to the standard output stream (stderr).
	 *
	 * <p>For each element of the DataStream the result of {@link Object#toString()} is written.
	 *
	 * <p>NOTE: This will print to stderr on the machine where the code is executed, i.e. the Flink
	 * worker.
	 *
	 * @param sinkIdentifier The string to prefix the output with.
	 * @return The closed DataStream.
	 */
	@PublicEvolving
	public DataStreamSink<T> printToErr(String sinkIdentifier) {
		PrintSinkFunction<T> printFunction = new PrintSinkFunction<>(sinkIdentifier, true);
		return addSink(printFunction).name("Print to Std. Err");
	}

	/**
	 * Writes a DataStream to the file specified by path in text format.
	 *
	 * <p>For every element of the DataStream the result of {@link Object#toString()} is written.
	 *
	 * @param path
	 *            The path pointing to the location the text file is written to.
	 *
	 * @return The closed DataStream.
	 */
	@PublicEvolving
	public DataStreamSink<T> writeAsText(String path) {
		return writeUsingOutputFormat(new TextOutputFormat<T>(new Path(path)));
	}


	/**
	 * Writes a DataStream to the file specified by path in text format.
	 *
	 * <p>For every element of the DataStream the result of {@link Object#toString()} is written.
	 *
	 * @param path
	 *            The path pointing to the location the text file is written to
	 * @param writeMode
	 *            Controls the behavior for existing files. Options are
	 *            NO_OVERWRITE and OVERWRITE.
	 *
	 * @return The closed DataStream.
	 */
	@PublicEvolving
	public DataStreamSink<T> writeAsText(String path, WriteMode writeMode) {
		TextOutputFormat<T> tof = new TextOutputFormat<>(new Path(path));
		tof.setWriteMode(writeMode);
		return writeUsingOutputFormat(tof);
	}


	/**
	 * Writes a DataStream to the file specified by the path parameter.
	 *
	 * <p>For every field of an element of the DataStream the result of {@link Object#toString()}
	 * is written. This method can only be used on data streams of tuples.
	 *
	 * @param path
	 *            the path pointing to the location the text file is written to
	 *
	 * @return the closed DataStream
	 */
	@PublicEvolving
	public DataStreamSink<T> writeAsCsv(String path) {
		return writeAsCsv(path, null, CsvOutputFormat.DEFAULT_LINE_DELIMITER, CsvOutputFormat.DEFAULT_FIELD_DELIMITER);
	}


	/**
	 * Writes a DataStream to the file specified by the path parameter.
	 *
	 * <p>For every field of an element of the DataStream the result of {@link Object#toString()}
	 * is written. This method can only be used on data streams of tuples.
	 *
	 * @param path
	 *            the path pointing to the location the text file is written to
	 * @param writeMode
	 *            Controls the behavior for existing files. Options are
	 *            NO_OVERWRITE and OVERWRITE.
	 *
	 * @return the closed DataStream
	 */
	@PublicEvolving
	public DataStreamSink<T> writeAsCsv(String path, WriteMode writeMode) {
		return writeAsCsv(path, writeMode, CsvOutputFormat.DEFAULT_LINE_DELIMITER, CsvOutputFormat.DEFAULT_FIELD_DELIMITER);
	}

	/**
	 * Writes a DataStream to the file specified by the path parameter. The
	 * writing is performed periodically every millis milliseconds.
	 *
	 * <p>For every field of an element of the DataStream the result of {@link Object#toString()}
	 * is written. This method can only be used on data streams of tuples.
	 *
	 * @param path
	 *            the path pointing to the location the text file is written to
	 * @param writeMode
	 *            Controls the behavior for existing files. Options are
	 *            NO_OVERWRITE and OVERWRITE.
	 * @param rowDelimiter
	 *            the delimiter for two rows
	 * @param fieldDelimiter
	 *            the delimiter for two fields
	 *
	 * @return the closed DataStream
	 */
	@SuppressWarnings("unchecked")
	@PublicEvolving
	public <X extends Tuple> DataStreamSink<T> writeAsCsv(
			String path,
			WriteMode writeMode,
			String rowDelimiter,
			String fieldDelimiter) {
		Preconditions.checkArgument(
			getType().isTupleType(),
			"The writeAsCsv() method can only be used on data streams of tuples.");

		CsvOutputFormat<X> of = new CsvOutputFormat<>(
			new Path(path),
			rowDelimiter,
			fieldDelimiter);

		if (writeMode != null) {
			of.setWriteMode(writeMode);
		}

		return writeUsingOutputFormat((OutputFormat<T>) of);
	}

	/**
	 * Writes the DataStream to a socket as a byte array. The format of the
	 * output is specified by a {@link SerializationSchema}.
	 *
	 * @param hostName
	 *            host of the socket
	 * @param port
	 *            port of the socket
	 * @param schema
	 *            schema for serialization
	 * @return the closed DataStream
	 */
	@PublicEvolving
	public DataStreamSink<T> writeToSocket(String hostName, int port, SerializationSchema<T> schema) {
		DataStreamSink<T> returnStream = addSink(new SocketClientSink<>(hostName, port, schema, 0));
		returnStream.setParallelism(1); // It would not work if multiple instances would connect to the same port
		return returnStream;
	}

	/**
	 * Writes the dataStream into an output, described by an OutputFormat.
	 *
	 * <p>The output is not participating in Flink's checkpointing!
	 *
	 * <p>For writing to a file system periodically, the use of the "flink-connector-filesystem"
	 * is recommended.
	 *
	 * @param format The output format
	 * @return The closed DataStream
	 */
	@PublicEvolving
	public DataStreamSink<T> writeUsingOutputFormat(OutputFormat<T> format) {
		return addSink(new OutputFormatSinkFunction<>(format));
	}

	/**
	 * Method for passing user defined operators along with the type
	 * information that will transform the DataStream.
	 *
	 * @param operatorName
	 *            name of the operator, for logging purposes
	 * @param outTypeInfo
	 *            the output type of the operator
	 * @param operator
	 *            the object containing the transformation logic
	 * @param <R>
	 *            type of the return stream
	 * @return the data stream constructed
	 */
	/**
	 * 传递用户定义的操作符，同时转换 DataStream 的方法
	 */
	@PublicEvolving
	public <R> SingleOutputStreamOperator<R> transform(String operatorName, TypeInformation<R> outTypeInfo, OneInputStreamOperator<T, R> operator) {

		// read the output type of the input Transform to coax out errors about MissingTypeInfo
		transformation.getOutputType();

		OneInputTransformation<T, R> resultTransform = new OneInputTransformation<>(
				this.transformation,
				operatorName,
				operator,
				outTypeInfo,
				environment.getParallelism());

		@SuppressWarnings({ "unchecked", "rawtypes" })
		SingleOutputStreamOperator<R> returnStream = new SingleOutputStreamOperator(environment, resultTransform);

		// 给执行环境的 transformations 加入一个 算子
		getExecutionEnvironment().addOperator(resultTransform);

		return returnStream;
	}

	/**
	 * Internal function for setting the partitioner for the DataStream.
	 *
	 * @param partitioner
	 *            Partitioner to set.
	 * @return The modified DataStream.
	 */
	/**
	 * 为 DataStream 设置 Partitioner
	 */
	protected DataStream<T> setConnectionType(StreamPartitioner<T> partitioner) {
		return new DataStream<>(this.getExecutionEnvironment(), new PartitionTransformation<>(this.getTransformation(), partitioner));
	}

	/**
	 * Adds the given sink to this DataStream. Only streams with sinks added
	 * will be executed once the {@link StreamExecutionEnvironment#execute()}
	 * method is called.
	 *
	 * @param sinkFunction
	 *            The object containing the sink's invoke function.
	 * @return The closed DataStream.
	 */
	/**
	 * 将给定的 sink 加入到 DataStream
	 * 只有添加了 sink 操作的流在 env.execute() 的时候会被执行
	 */
	public DataStreamSink<T> addSink(SinkFunction<T> sinkFunction) {

		// read the output type of the input Transform to coax out errors about MissingTypeInfo
		transformation.getOutputType();

		// configure the type if needed
		if (sinkFunction instanceof InputTypeConfigurable) {
			((InputTypeConfigurable) sinkFunction).setInputType(getType(), getExecutionConfig());
		}

		StreamSink<T> sinkOperator = new StreamSink<>(clean(sinkFunction));

		DataStreamSink<T> sink = new DataStreamSink<>(this, sinkOperator);

		getExecutionEnvironment().addOperator(sink.getTransformation());
		return sink;
	}

	/**
	 * Returns the {@link StreamTransformation} that represents the operation that logically creates
	 * this {@link DataStream}.
	 *
	 * @return The Transformation
	 */
	@Internal
	public StreamTransformation<T> getTransformation() {
		return transformation;
	}
}
