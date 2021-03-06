/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.windowing.triggers;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.state.ReducingState;
import org.apache.flink.api.common.state.ReducingStateDescriptor;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.Window;

/**
 * A {@link Trigger} that continuously fires based on a given time interval as measured by
 * the clock of the machine on which the job is running.
 *
 * @param <W> The type of {@link Window Windows} on which this trigger can operate.
 */
/**
 * 一种触发器，根据给定的时间间隔连续触发，时间间隔是由机器时间度量的
 */
@PublicEvolving
public class ContinuousProcessingTimeTrigger<W extends Window> extends Trigger<Object, W> {
	private static final long serialVersionUID = 1L;

	private final long interval;

	/** When merging we take the lowest of all fire timestamps as the new fire timestamp. */
	// 当合并的时候，我们选择所有触发时间中的最小值作为新的触发时间
	private final ReducingStateDescriptor<Long> stateDesc =
			new ReducingStateDescriptor<>("fire-time", new Min(), LongSerializer.INSTANCE);

	private ContinuousProcessingTimeTrigger(long interval) {
		this.interval = interval;
	}

	@Override
	public TriggerResult onElement(Object element, long timestamp, W window, TriggerContext ctx) throws Exception {
		ReducingState<Long> fireTimestamp = ctx.getPartitionedState(stateDesc);

		timestamp = ctx.getCurrentProcessingTime();

		// 第一次注册，之后都会在 onProcessingTime 的时候再次注册
		if (fireTimestamp.get() == null) {
			long start = timestamp - (timestamp % interval);
			long nextFireTimestamp = start + interval;  // 计算下一次触发的时间

			ctx.registerProcessingTimeTimer(nextFireTimestamp);

			fireTimestamp.add(nextFireTimestamp);
			return TriggerResult.CONTINUE;
		}
		return TriggerResult.CONTINUE;
	}

	@Override
	public TriggerResult onEventTime(long time, W window, TriggerContext ctx) throws Exception {
		return TriggerResult.CONTINUE;
	}

	@Override
	public TriggerResult onProcessingTime(long time, W window, TriggerContext ctx) throws Exception {
		ReducingState<Long> fireTimestamp = ctx.getPartitionedState(stateDesc);

		if (fireTimestamp.get().equals(time)) {
			fireTimestamp.clear();
			fireTimestamp.add(time + interval);
			ctx.registerProcessingTimeTimer(time + interval);  // 再次注册
			return TriggerResult.FIRE;
		}
		return TriggerResult.CONTINUE;
	}

	@Override
	public void clear(W window, TriggerContext ctx) throws Exception {
		ReducingState<Long> fireTimestamp = ctx.getPartitionedState(stateDesc);
		long timestamp = fireTimestamp.get();
		ctx.deleteProcessingTimeTimer(timestamp);  // 删除最后注册的定时器
		fireTimestamp.clear();  // 清空状态
	}

	@Override
	public boolean canMerge() {
		return true;
	}

	@Override
	public void onMerge(W window,
			OnMergeContext ctx) {
		ctx.mergePartitionedState(stateDesc);
	}

	@VisibleForTesting
	public long getInterval() {
		return interval;
	}

	@Override
	public String toString() {
		return "ContinuousProcessingTimeTrigger(" + interval + ")";
	}

	/**
	 * Creates a trigger that continuously fires based on the given interval.
	 *
	 * @param interval The time interval at which to fire.
	 * @param <W> The type of {@link Window Windows} on which this trigger can operate.
	 */
	public static <W extends Window> ContinuousProcessingTimeTrigger<W> of(Time interval) {
		return new ContinuousProcessingTimeTrigger<>(interval.toMilliseconds());
	}

	private static class Min implements ReduceFunction<Long> {
		private static final long serialVersionUID = 1L;

		@Override
		public Long reduce(Long value1, Long value2) throws Exception {
			return Math.min(value1, value2);
		}
	}
}
