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
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;

/**
 * A {@link StreamOperator} for executing {@link SinkFunction SinkFunctions}.
 */
/**
 * 一个流操作符用于执行 Sink 操作
 */
@Internal
public class StreamSink<IN> extends AbstractUdfStreamOperator<Object, SinkFunction<IN>>
		implements OneInputStreamOperator<IN, Object> {

	private static final long serialVersionUID = 1L;

	private transient SimpleContext sinkContext;

	/** We listen to this ourselves because we don't have an {@link InternalTimerService}. */
	/**
	 * 我们自己监听，因为没有 InternalTimerService
	 */
	private long currentWatermark = Long.MIN_VALUE;

	public StreamSink(SinkFunction<IN> sinkFunction) {
		super(sinkFunction);
		chainingStrategy = ChainingStrategy.ALWAYS;  // 在当前 Thread sink
	}

	@Override
	public void open() throws Exception {
		super.open();

		this.sinkContext = new SimpleContext<>(getProcessingTimeService());
	}

	@Override
	public void processElement(StreamRecord<IN> element) throws Exception {
		sinkContext.element = element;
		userFunction.invoke(element.getValue(), sinkContext);
	}

	@Override
	protected void reportOrForwardLatencyMarker(LatencyMarker marker) {
		// all operators are tracking latencies
		// 所有的操作符都要处理延迟
		this.latencyStats.reportLatency(marker);

		// sinks don't forward latency markers
	}

	@Override
	public void processWatermark(Watermark mark) throws Exception {
		super.processWatermark(mark);
		this.currentWatermark = mark.getTimestamp();
	}

	/**
	 * 简单的 SinkFunction.Context 的实现
	 */
	private class SimpleContext<IN> implements SinkFunction.Context<IN> {

		private StreamRecord<IN> element;  // 当前最后处理的 StreamRecord

		private final ProcessingTimeService processingTimeService;  // 通用的进程时间服务

		public SimpleContext(ProcessingTimeService processingTimeService) {
			this.processingTimeService = processingTimeService;
		}

		@Override
		/**
		 * 获取当前的时间
		 */
		public long currentProcessingTime() {
			return processingTimeService.getCurrentProcessingTime();
		}

		@Override
		/**
		 * 获取当前的 watermark
		 */
		public long currentWatermark() {
			return currentWatermark;
		}

		@Override
		/**
		 * 获取当前 StreamRecord 的 ts
		 */
		public Long timestamp() {
			if (element.hasTimestamp()) {
				return element.getTimestamp();
			}
			return null;
		}
	}
}
