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

package org.apache.flink.streaming.api.operators;

import org.apache.flink.annotation.Internal;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MetricOptions;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusMaintainer;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeCallback;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;

import java.util.concurrent.ScheduledFuture;

/**
 * {@link StreamOperator} for streaming sources.
 *
 * @param <OUT> Type of the output elements
 * @param <SRC> Type of the source function of this stream source operator
 */
/**
 * 数据流的源头
 * @param <OUT> 输出的类型
 * @param <SRC> 数据源操作符的数据源函数
 */
@Internal
public class StreamSource<OUT, SRC extends SourceFunction<OUT>> extends AbstractUdfStreamOperator<OUT, SRC> {

	private static final long serialVersionUID = 1L;

	private transient SourceFunction.SourceContext<OUT> ctx;  // 从数据源 emit element

	private transient volatile boolean canceledOrStopped = false;  // 标识 StreamSource 是否停止

	public StreamSource(SRC sourceFunction) {
		super(sourceFunction);

		this.chainingStrategy = ChainingStrategy.HEAD;
	}

	// run 方法在 SourceStreamTask 的 run 方法中被调用
	public void run(final Object lockingObject, final StreamStatusMaintainer streamStatusMaintainer) throws Exception {
		run(lockingObject, streamStatusMaintainer, output);
	}

	public void run(final Object lockingObject,
			final StreamStatusMaintainer streamStatusMaintainer,
			final Output<StreamRecord<OUT>> collector) throws Exception {

		final TimeCharacteristic timeCharacteristic = getOperatorConfig().getTimeCharacteristic();  // 获取当前操作符的 TimeCharacteristic

		final Configuration configuration = this.getContainingTask().getEnvironment().getTaskManagerInfo().getConfiguration();
		final long latencyTrackingInterval = getExecutionConfig().isLatencyTrackingConfigured()
			? getExecutionConfig().getLatencyTrackingInterval()
			: configuration.getLong(MetricOptions.LATENCY_INTERVAL);

		LatencyMarksEmitter<OUT> latencyEmitter = null;
		if (latencyTrackingInterval > 0) {
			// 如果设置了延迟追踪时间间隔，那么我们需要定义一个 LatencyMarksEmitter
			latencyEmitter = new LatencyMarksEmitter<>(
				getProcessingTimeService(),
				collector,
				latencyTrackingInterval,
				this.getOperatorID(),  // 由 primaryHash 生成
				getRuntimeContext().getIndexOfThisSubtask());
		}

		// 获取 Config 文件中发出 watermark 的时间间隔
		final long watermarkInterval = getRuntimeContext().getExecutionConfig().getAutoWatermarkInterval();

		this.ctx = StreamSourceContexts.getSourceContext(
			timeCharacteristic,
			getProcessingTimeService(),
			lockingObject,
			streamStatusMaintainer,
			collector,
			watermarkInterval,
			-1);

		try {
			// 调用 SourceFunction 的 run 方法来 emit element
			// SourceFunction 中的 run 方法在 emit record 的时候
			// 都是调用 ctx 中的方法
			userFunction.run(ctx);

			// if we get here, then the user function either exited after being done (finite source)
			// or the function was canceled or stopped. For the finite source case, we should emit
			// a final watermark that indicates that we reached the end of event-time
			// 如果代码运行到了这里，说明 UDF 数据源程序退出了或者数据 emit 完毕了
			// 如果是有限数据 emit 完毕了，我们 emit 一个 final watermark
			// 告诉后续 operator 没有更多数据了
			if (!isCanceledOrStopped()) {
				ctx.emitWatermark(Watermark.MAX_WATERMARK);
			}
		} finally {
			// make sure that the context is closed in any case
			ctx.close();
			if (latencyEmitter != null) {
				latencyEmitter.close();
			}
		}
	}

	public void cancel() {
		// important: marking the source as stopped has to happen before the function is stopped.
		// the flag that tracks this status is volatile, so the memory model also guarantees
		// the happens-before relationship
		markCanceledOrStopped();
		userFunction.cancel();

		// the context may not be initialized if the source was never running.
		if (ctx != null) {
			ctx.close();
		}
	}

	/**
	 * Marks this source as canceled or stopped.
	 *
	 * <p>This indicates that any exit of the {@link #run(Object, StreamStatusMaintainer, Output)} method
	 * cannot be interpreted as the result of a finite source.
	 */
	/**
	 * 标识数据源被取消或停止
	 * run 方法的退出并不能被认为数据流结束了
	 */
	protected void markCanceledOrStopped() {
		this.canceledOrStopped = true;
	}

	/**
	 * Checks whether the source has been canceled or stopped.
	 * @return True, if the source is canceled or stopped, false is not.
	 */
	/**
	 * 检查 source 是否已经被 canceled 或 stopped
	 */
	protected boolean isCanceledOrStopped() {
		return canceledOrStopped;
	}

	private static class LatencyMarksEmitter<OUT> {
		private final ScheduledFuture<?> latencyMarkTimer;

		public LatencyMarksEmitter(
				final ProcessingTimeService processingTimeService,
				final Output<StreamRecord<OUT>> output,
				long latencyTrackingInterval,
				final OperatorID operatorId,
				final int subtaskIndex) {

			latencyMarkTimer = processingTimeService.scheduleAtFixedRate(
				new ProcessingTimeCallback() {
					@Override
					public void onProcessingTime(long timestamp) throws Exception {
						try {
							// ProcessingTimeService callbacks are executed under the checkpointing lock
							// ProcessingTimeService 的回调在 checkpointing 锁下执行
							output.emitLatencyMarker(new LatencyMarker(timestamp, operatorId, subtaskIndex));
						} catch (Throwable t) {
							// we catch the Throwables here so that we don't trigger the processing
							// timer services async exception handler
							LOG.warn("Error while emitting latency marker.", t);
						}
					}
				},
				0L,
				latencyTrackingInterval);
		}

		public void close() {
			latencyMarkTimer.cancel(true);
		}
	}
}
