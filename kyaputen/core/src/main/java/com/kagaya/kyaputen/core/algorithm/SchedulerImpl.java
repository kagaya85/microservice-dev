package com.kagaya.kyaputen.core.algorithm;


import afu.org.checkerframework.checker.oigj.qual.O;
import com.kagaya.kyaputen.common.metadata.tasks.TaskDefinition;
import com.kagaya.kyaputen.common.runtime.Node;
import com.kagaya.kyaputen.common.runtime.Pod;
import com.kagaya.kyaputen.common.runtime.Pod.PodStatus;
import com.kagaya.kyaputen.common.schedule.DeploymentPlan;
import com.kagaya.kyaputen.common.schedule.ExecutionPlan;
import com.kagaya.kyaputen.common.metadata.workflow.WorkflowDefinition;
import com.kagaya.kyaputen.common.schedule.TaskExecutionPlan;
import com.kagaya.kyaputen.core.algorithm.methods.DemoExecutionPlanGenerator;
import com.kagaya.kyaputen.core.algorithm.methods.Method;
import com.kagaya.kyaputen.core.config.Constant;
import com.kagaya.kyaputen.core.config.K8sConfig;
import com.kagaya.kyaputen.core.config.Price;
import com.kagaya.kyaputen.core.dao.NodeResourceDAO;
import com.kagaya.kyaputen.core.dao.PodResourceDAO;
import com.kagaya.kyaputen.core.metrics.Monitor;
import com.kagaya.kyaputen.core.service.KubernetesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SchedulerImpl implements Scheduler {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerImpl.class);

    /**
     * 利用统计信息更新任务量预测
     * @param workflowDef
     */
    public void updateTaskSize(WorkflowDefinition workflowDef) {

        logger.debug("Update task size of workflow: {}", workflowDef.getName());

        for (String tdn: workflowDef.getTaskDefNames()) {
            TaskDefinition td = workflowDef.getTaskDef(tdn);
            long taskSize = (long)(Monitor.getTaskExecutionTime(td.getTaskType()) * workflowDef.getCeByType(td.getTaskType()));
            td.setTaskSize(taskSize);
            logger.debug("Update task size: {}, of taskType: {}", taskSize, td.getTaskType());
        }
    }

    /**
     * 计算工作流中每个任务的资源需求量CE，写入工作流定义
     * @param workflowDef - 工作流定义
     * @param deadlineFactor - 最后期限管理严格成都
     */
    @Override
    public void calcWorkflowCostEfficient(WorkflowDefinition workflowDef, double deadlineFactor) {
        Map<String, Double> ce = new HashMap<>();
        Map<String, Integer> taskTypeNum = workflowDef.getTaskTypeNums();

        logger.debug("Calculate Workflow: {} Cost Efficient", workflowDef.getName());

        updateTaskSize(workflowDef);

        for (String type: taskTypeNum.keySet()) {
            ce.put(type, Constant.POD_MIN_CU);
        }

        // 原始执行时间
        long expectedExecutionTime = calcExpectedExecutionTime(workflowDef, ce);
        logger.info("Workflow: {}, expected execution time: {}, time limit: {}", workflowDef.getName(), expectedExecutionTime, workflowDef.getTimeLimit());

        // 调整cu，计算性价比
        while(true) {

            if (expectedExecutionTime < workflowDef.getTimeLimit()) {
                logger.info("Workflow: {}, expected execution time: {}, time limit: {}", workflowDef.getName(), expectedExecutionTime, workflowDef.getTimeLimit());
                break;
            }

            String speedupType = null;
            double maxGain = 0.0;
            long maxReduceExeTime = 0;
            int costFlag = 1;   // 1 - 增长， 0 - 不变， -1 - 减少
            // 调整单个cu，搜索加速比最高的任务类型
            for (String taskType: ce.keySet()) {
                double oldCU = ce.get(taskType);
                long oldExeTime = (long)Math.ceil(workflowDef.getTaskSize(taskType) / oldCU);
                double oldCost = calcTaskCost(ce.get(taskType), workflowDef.getTaskSize(taskType), taskTypeNum.get(taskType));

                if (oldCU >= Constant.MAX_CU)
                    continue;

                double newCU = oldCU + Constant.CU_INTERVAL;
                long newExeTime = (long)Math.ceil(workflowDef.getTaskSize(taskType) / newCU);
                double newCost = calcTaskCost(newCU, workflowDef.getTaskSize(taskType), taskTypeNum.get(taskType));

                double gain;
                long reduceExeTime;

                if (newCost - oldCost < 0) {
                    // 费用减少
                    gain = oldCost - newCost;
                    reduceExeTime = oldExeTime - newExeTime;
                    if (gain > maxGain + Constant.E || costFlag >= 0) {
                        maxGain = gain;
                        speedupType = taskType;
                        maxReduceExeTime = reduceExeTime;
                        costFlag = -1;
                    } else if (Math.abs(gain - maxGain) < Constant.E) {
                        if (reduceExeTime > maxReduceExeTime) {
                            maxGain = gain;
                            speedupType = taskType;
                            maxReduceExeTime = reduceExeTime;
                            costFlag = -1;
                        }
                    }
                }
                else if (costFlag >=0 && Math.abs(newCost - oldCost) < Constant.E) {
                    // 费用相等
                    gain = oldExeTime - newExeTime;
                    if (gain > maxGain + Constant.E || costFlag == 1) {
                        maxGain = gain;
                        speedupType = taskType;
                        costFlag = 0;
                    }
                }
                else if (costFlag > 0) {
                    // 费用增加
                    gain = (oldExeTime - newExeTime) / (newCost - oldCost);
                    if (gain > maxGain + Constant.E) {
                        maxGain = gain;
                        speedupType = taskType;
                    }
                }

            } // for end

            if (speedupType == null) {
                // 没有满足的taskType，本次搜索结束
                break;
            } else {
                double oldCU = ce.get(speedupType);
                if (oldCU < Constant.MAX_CU) {
                    ce.put(speedupType, oldCU + Constant.CU_INTERVAL);
                }
            }

            expectedExecutionTime = calcExpectedExecutionTime(workflowDef, ce);
        }

        logger.debug("Calculate CostEfficient of workflow: {}, ce: {}", workflowDef.getName(), ce);

        workflowDef.setCEMap(ce);
        markUpdatePod(workflowDef);
    }

    @Override
    public void markUpdatePod(WorkflowDefinition workflowDef) {
        PodResourceDAO podResourceDAO = new PodResourceDAO();

        List<String> podIdList = podResourceDAO.getPodIdList();
        for(String id: podIdList) {
            Pod pod = podResourceDAO.getPod(id);
            double ce = workflowDef.getCeByType(pod.getTaskImageName());

            if (pod.getComputeUnit() < ce + Constant.E) {
                pod.setNeedUpdate();
                logger.debug("Pod need to be updated. Id: {}, ImageName {} ", pod.getPodId(), pod.getTaskImageName());
            }
        }
    }

    /**
     * 计算工作流的期望执行时间，同时更新每个任务定义的期望开始时间与期望完成时间
     * @param workflowDef
     * @param ce
     * @return
     */
    private long calcExpectedExecutionTime(WorkflowDefinition workflowDef, Map<String, Double> ce) {
        logger.debug("Calculate expected execution time of workflow: {}", workflowDef.getName());

        long expectedExecutionTime = 0;
        List<String> taskQueue = new LinkedList<>();
        TaskDefinition td = workflowDef.getStartTaskDefinition();
        taskQueue.add(td.getTaskDefName());

        // BFS
        while (!taskQueue.isEmpty()) {
            td = workflowDef.getTaskDef(taskQueue.get(0));
            taskQueue.remove(0);

            List<String> priorTasks = td.getPriorTasks();
            long arrivalTime = 0;
            long absoluteStartTime = 0;

            if (priorTasks.size() != 0) {
                for (String taskName: priorTasks) {
                    arrivalTime = workflowDef.getTaskDef(taskName).getAbsoluteFinishTime();
                    arrivalTime += Monitor.getTaskRecentLatencyTime(workflowDef.getTaskDef(taskName).getTaskType());
                    absoluteStartTime = Math.max(absoluteStartTime, arrivalTime);
                }
            }

            double cu = ce.get(td.getTaskType());
            long executionTime = (long)Math.ceil(td.getTaskSize() / cu);
            long absoluteFinishTime = absoluteStartTime + executionTime;
            td.setAbsoluteStartTime(absoluteStartTime);
            td.setAbsoluteFinishTime(absoluteFinishTime);
            expectedExecutionTime = Math.max(expectedExecutionTime, absoluteFinishTime);

            for (String nt: td.getNextTasks()) {
                if (!taskQueue.contains(nt))
                    taskQueue.add(nt);
            }
        }
        logger.debug("Workflow: {} expected execution time: {}", workflowDef.getName(), expectedExecutionTime);

        return expectedExecutionTime;
    }

    private void updateExpectedExecutionTime(WorkflowDefinition workflowDef) {

        long expectedExecutionTime = 0;
        List<String> taskQueue = new LinkedList<>();
        TaskDefinition td = workflowDef.getStartTaskDefinition();
        taskQueue.add(td.getTaskDefName());

        // BFS
        while (!taskQueue.isEmpty()) {
            td = workflowDef.getTaskDef(taskQueue.get(0));
            taskQueue.remove(0);
            List<String> priorTasks = td.getPriorTasks();

            long arrivalTime = 0;
            long absoluteStartTime = 0;

            if (priorTasks.size() != 0)
                for (String taskName: priorTasks) {
                    arrivalTime = workflowDef.getTaskDef(taskName).getAbsoluteFinishTime();
                    arrivalTime += Monitor.getTaskRecentLatencyTime(workflowDef.getTaskDef(taskName).getTaskType());
                    absoluteStartTime = Math.max(absoluteStartTime, arrivalTime);
                }

            double cu = workflowDef.getCeByType(td.getTaskType());
            long executionTime = (long)Math.ceil(td.getTaskSize() / cu);
            long absoluteFinishTime = absoluteStartTime + executionTime;
            td.setAbsoluteStartTime(absoluteStartTime);
            td.setAbsoluteFinishTime(absoluteFinishTime);
            expectedExecutionTime = Math.max(expectedExecutionTime, absoluteFinishTime);

            for (String nt: td.getNextTasks()) {
                if (!taskQueue.contains(nt))
                    taskQueue.add(nt);
            }
        }

        logger.debug("Update Expected Execution Time of workflow: {}", workflowDef.getName());
    }

    /**
     * 计算任务成本
     * @param cu 计算资源量
     * @param taskSize 任务量
     * @param num 任务数量
     * @return
     */
    private double calcTaskCost(double cu, long taskSize, int num) {
        return Price.getNodeUnitPrice("Test_Node") * cu * Math.ceil(taskSize / cu / Constant.PRICE_INTERVAL) * num;
    }

    /**
     * 划分子截止时间
     * @param workflowDef 工作流定义
     * @param startTime 工作流计划开始时间
     * @param deadline 工作流截止日期
     */
    public void divideSubDeadline(WorkflowDefinition workflowDef, long startTime, long deadline) {

        TaskDefinition taskDef = workflowDef.getEndTaskDefinition();
        List<String> taskQueue = new LinkedList<>(taskDef.getPriorTasks());

        logger.debug("Divide deadline or workflow: {}, startTime: {}, deadline: {}, timeLimit: {}", workflowDef.getName(), startTime, deadline, deadline - startTime);

        double ce = workflowDef.getCeByType(taskDef.getTaskType());
        long rankTime = (long)Math.ceil(taskDef.getTaskSize() / ce);
        taskDef.setRankTime(rankTime);

        // BFS 计算Rank
        while (!taskQueue.isEmpty()) {
            long maxRank = 0;
            taskDef = workflowDef.getTaskDef(taskQueue.get(0));
            taskQueue.remove(0);

            ce = workflowDef.getCeByType(taskDef.getTaskType());

            for (String nextTask: taskDef.getNextTasks()) {
                TaskDefinition td = workflowDef.getTaskDef(nextTask);
                long latencyTime = Monitor.getTaskRecentLatencyTime(nextTask);
                long nextRank = td.getRankTime();

                maxRank = Math.max(maxRank, nextRank + latencyTime);
            }

            rankTime = maxRank + (long)Math.ceil(taskDef.getTaskSize() / ce);
            taskDef.setRankTime(rankTime);

            for (String pt: taskDef.getPriorTasks()) {
                if (!taskQueue.contains(pt)) {
                    taskQueue.add(pt);
                }
            }
        } // while end

        // 划分子截止时间
        long totTimeLimit = deadline - startTime;
        if (totTimeLimit < 0) {
            logger.error("Deadline before the startTime in divide subdeadline");
        }
        long totRank = workflowDef.getStartTaskDefinition().getRankTime();
        logger.debug("Total rank: {}", totRank);

        for (String tdn: workflowDef.getTaskDefNames()) {
            TaskDefinition td = workflowDef.getTaskDef(tdn);
            long executionTime = (long)Math.floor(td.getTaskSize() / workflowDef.getCeByType(td.getTaskType()));
            long timeLimit = totTimeLimit * executionTime / totRank;
            td.setTimeLimit(timeLimit);
            logger.debug("Task: {}, timeLimit: {}", td.getTaskType(), timeLimit);
        }
    }

    /**
     * 生成执行方案
     */
    @Override
    public ExecutionPlan genExecutionPlan(long startTime, WorkflowDefinition workflowDef) {

        logger.debug("Workflow: {} start generate Execution Plan ", workflowDef.getName());

        Method method = new DemoExecutionPlanGenerator(workflowDef, startTime);

        // 用新的CE配置更新下每个任务的执行时间，用于估计实际执行时间，计算紧急程度
        updateExpectedExecutionTime(workflowDef);

        return method.schedule();
    }

    /**
     * 应用调度方案，包括申请资源
     */
    @Override
    public void deploy(WorkflowDefinition workflowDef, ExecutionPlan executionPlan) {

        KubernetesService k8s = new KubernetesService();
        PodResourceDAO podResourceDAO = new PodResourceDAO();
        NodeResourceDAO nodeResourceDAO = new NodeResourceDAO();

        logger.debug("Workflow: {} deploying...", workflowDef.getName());

        for (TaskExecutionPlan plan: executionPlan.getTaskExecutionPlans()) {
            logger.debug("TaskExecutionPlan: {}", plan);
            Pod pod = podResourceDAO.getPod(plan.getPodId());
            Node node = nodeResourceDAO.getNode(plan.getNodeName());

            if (node.getStatus().isDown()) {
                // 启动node
                k8s.startNode(node);
            }

            if (pod.getStatus().equals(PodStatus.NEW)) {
                // 新建立的pod需部署到对应node上
                k8s.createPod(pod, workflowDef.getCeByType(plan.getTaskType()));
            } else if (pod.getStatus().equals(PodStatus.IDLE)) {
                if (pod.needUpdate())
                    k8s.resizePod(pod, workflowDef.getCeByType(plan.getTaskType()));
            } else {
                // 其他
                k8s.startNode(node);
            }
        }
    }


}