package com.bonree.brfs.schedulers.task.operation.impl;

import java.util.List;

import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bonree.brfs.common.task.TaskState;
import com.bonree.brfs.common.task.TaskType;
import com.bonree.brfs.common.utils.BrStringUtils;
import com.bonree.brfs.common.utils.JsonUtils;
import com.bonree.brfs.common.utils.Pair;
import com.bonree.brfs.schedulers.ManagerContralFactory;
import com.bonree.brfs.schedulers.jobs.JobDataMapConstract;
import com.bonree.brfs.schedulers.task.manager.MetaTaskManagerInterface;
import com.bonree.brfs.schedulers.task.model.TaskModel;
import com.bonree.brfs.schedulers.task.model.TaskResultModel;
import com.bonree.brfs.schedulers.task.model.TaskServerNodeModel;

public class TaskStateLifeContral {
	private static final Logger LOG = LoggerFactory.getLogger("TaskLife");
	
	/**
	 * 概述：获取当前任务
	 * @param release
	 * @param prexTaskName
	 * @param taskType
	 * @param serverId
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static String getcurrentTaskName(MetaTaskManagerInterface release, String prexTaskName,TaskType taskType, String serverId){
		String typeName = taskType.name();
		String currentTaskName = null;
		if(BrStringUtils.isEmpty(prexTaskName)){
			prexTaskName = release.getLastSuccessTaskIndex(typeName, serverId);
		}
		if(BrStringUtils.isEmpty(prexTaskName)|| !BrStringUtils.isEmpty(prexTaskName)&& release.queryTaskState(prexTaskName, typeName) < 0){
			currentTaskName = release.getFirstServerTask(typeName, serverId);
		}else{
			currentTaskName = release.getNextTaskName(typeName, prexTaskName);
		}
		LOG.info("type: {},  prexTaskName :{} , currentTaskName: {}", typeName, prexTaskName, currentTaskName);
		return currentTaskName;
	}
	/**
	 * 概述：获取任务信息
	 * @param release
	 * @param prexTaskName
	 * @param serviceId
	 * @return
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static Pair<String, TaskModel> getTaskModel(MetaTaskManagerInterface release,TaskType taskType,String prexTaskName, String serviceId){
		String currentTaskName = getcurrentTaskName(release, prexTaskName, taskType, serviceId);
		if(BrStringUtils.isEmpty(currentTaskName)){
			return null;
		}
		TaskModel task = release.getTaskContentNodeInfo(taskType.name(), currentTaskName);
		return new Pair<String,TaskModel>(currentTaskName, task);
	}
	
	/**
	 * 概述：更新任务状态
	 * @param serverId
	 * @param taskname
	 * @param taskType
	 * @param result
	 * @param stat
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static void updateTaskStatusByCompelete(String serverId, String taskname,String taskType,String result, int stat){
		TaskResultModel taskResult = null;
		if(!BrStringUtils.isEmpty(result)){
			taskResult = JsonUtils.toObject(result, TaskResultModel.class);
		}
		if(BrStringUtils.isEmpty(taskname)){
			LOG.info("task name is empty !!! {} {} {}", taskType,taskname, serverId);
			return;
		}
		ManagerContralFactory mcf = ManagerContralFactory.getInstance();
		MetaTaskManagerInterface release = mcf.getTm();
		TaskServerNodeModel sTask = release.getTaskServerContentNodeInfo(taskType, taskname, serverId);
		if(sTask == null){
			LOG.info("server task is null !!! {} {} {}", taskType,taskname, serverId);
			sTask = new TaskServerNodeModel();
		}
		sTask.setResult(taskResult);
		sTask.setTaskStopTime(System.currentTimeMillis());
		sTask.setTaskState(stat);
		release.updateServerTaskContentNode(serverId, taskname, taskType, sTask);
		LOG.info("----> complete server task :{} - {} - {} - {}",taskType, taskname, serverId, TaskState.valueOf(sTask.getTaskState()).name());
		// 更新TaskContent
		List<Pair<String,Integer>> cStatus = release.getServerStatus(taskType, taskname);
		if(cStatus == null || cStatus.isEmpty()){
			return;
		}
		LOG.info("complete c List {}",cStatus);
		int cstat = -1;
		boolean isException = false;
		int finishCount = 0;
		int size = cStatus.size();
		for(Pair<String,Integer> pair : cStatus){
			cstat = pair.getValue();
			if(TaskState.EXCEPTION.code() == cstat){
				isException = true;
				finishCount +=1;
			}else if(TaskState.FINISH.code() == cstat){
				finishCount +=1;
			}
		}
		if(finishCount != size){
			return;
		}
		TaskModel task = release.getTaskContentNodeInfo(taskType, taskname);
		if(task == null){
			LOG.info("task is null !!! {} {} {}", taskType,taskname);
			task = new TaskModel();
			task.setCreateTime(System.currentTimeMillis());
		}
		if(isException){
			task.setTaskState(TaskState.EXCEPTION.code());
		}else{
			task.setTaskState(TaskState.FINISH.code());
		}
		release.updateTaskContentNode(task, taskType, taskname);
		LOG.info("----> complete task :{} - {} - {}",taskType, taskname, TaskState.valueOf(task.getTaskState()).name());
	}
	/**
	 * 概述：更新任务map的任务状态
	 * @param context
	 * @param stat
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static  void updateMapTaskMessage(JobExecutionContext context, TaskResultModel result){
		JobDataMap data  = context.getJobDetail().getJobDataMap();
		if(data == null){
			return ;
		}
		// 更新任务结果
		if(result == null){
			return;
		}
		int taskStat = -1;
		if(data.containsKey(JobDataMapConstract.TASK_MAP_STAT)){
			taskStat = data.getInt(JobDataMapConstract.TASK_MAP_STAT);
		}
		
		if(!(TaskState.EXCEPTION.code() == taskStat || TaskState.FINISH.code() == taskStat)){
			data.put(JobDataMapConstract.TASK_MAP_STAT, result.isSuccess() ? TaskState.FINISH.code() : TaskState.EXCEPTION.code());
		}else{
		}
		TaskResultModel sumResult = null;
		String content = null;
		if(data.containsKey(JobDataMapConstract.TASK_RESULT)){
			content = data.getString(JobDataMapConstract.TASK_RESULT);
		}
		if(!BrStringUtils.isEmpty(content)){
			sumResult = JsonUtils.toObject(content, TaskResultModel.class);
		}else{
			sumResult = new TaskResultModel();
		}
		sumResult.addAll(result.getAtoms());
		String sumContent = JsonUtils.toJsonString(sumResult);
		data.put(JobDataMapConstract.TASK_RESULT, sumContent);
		
	}
	
	/**
	 * 概述：将服务状态修改为RUN
	 * @param serverId
	 * @param taskname
	 * @param taskType
	 * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
	 */
	public static void updateTaskRunState(String serverId, String taskname,String taskType){
		ManagerContralFactory mcf = ManagerContralFactory.getInstance();
		MetaTaskManagerInterface release = mcf.getTm();
		int taskStat = release.queryTaskState(taskname, taskType);
		//修改服务几点状态，若不为RUN则修改为RUN
		TaskServerNodeModel serverNode = release.getTaskServerContentNodeInfo(taskType, taskname, serverId);
		if(serverNode == null){
			serverNode =new TaskServerNodeModel();
		}
		serverNode.setTaskStartTime(System.currentTimeMillis());
		serverNode.setTaskState(TaskState.RUN.code());
		release.updateServerTaskContentNode(serverId, taskname, taskType, serverNode);
		LOG.info("----> run server task :{} - {} - {} - {}",taskType, taskname, serverId, TaskState.valueOf(serverNode.getTaskState()).name());
		//查询任务节点状态，若不为RUN则获取分布式锁，修改为RUN
		if(taskStat != TaskState.RUN.code() ){
			TaskModel task = release.getTaskContentNodeInfo(taskType, taskname);
			if(task == null){
				task = new TaskModel();
			}
			task.setTaskState(TaskState.RUN.code());
			release.updateTaskContentNode(task, taskType, taskname);
			LOG.info("----> run task :{} - {} - {}",taskType, taskname,  TaskState.valueOf(task.getTaskState()).name());
		}
		
	}
}
