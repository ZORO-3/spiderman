package org.eweb4j.spiderman.spider;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eweb4j.spiderman.fetcher.FetchResult;
import org.eweb4j.spiderman.fetcher.Page;
import org.eweb4j.spiderman.plugin.BeginPoint;
import org.eweb4j.spiderman.plugin.DigPoint;
import org.eweb4j.spiderman.plugin.DoneException;
import org.eweb4j.spiderman.plugin.DupRemovalPoint;
import org.eweb4j.spiderman.plugin.EndPoint;
import org.eweb4j.spiderman.plugin.FetchPoint;
import org.eweb4j.spiderman.plugin.ParsePoint;
import org.eweb4j.spiderman.plugin.PojoPoint;
import org.eweb4j.spiderman.plugin.TargetPoint;
import org.eweb4j.spiderman.plugin.TaskPushPoint;
import org.eweb4j.spiderman.plugin.TaskSortPoint;
import org.eweb4j.spiderman.task.Task;
import org.eweb4j.spiderman.url.SourceUrlChecker;
import org.eweb4j.spiderman.xml.Field;
import org.eweb4j.spiderman.xml.Rule;
import org.eweb4j.spiderman.xml.Rules;
import org.eweb4j.spiderman.xml.Target;
import org.eweb4j.util.CommonUtil;


/**
 * 网络蜘蛛
 * @author weiwei
 *
 */
public class Spider implements Runnable{

	public Task task;
	public SpiderListener listener;
	
	public void init(Task task, SpiderListener listener) {
		this.task = task;
		this.listener = listener;
	}
	
	public void run() {
		long start = System.currentTimeMillis();
		this.listener.onInfo(Thread.currentThread(), task, "spider task begin " + task.url);
		try {
			//扩展点：begin 蜘蛛开始
			Collection<BeginPoint> beginPoints = task.site.beginPointImpls;
			if (beginPoints != null && !beginPoints.isEmpty()){
				for (Iterator<BeginPoint> it = beginPoints.iterator(); it.hasNext(); ){
					BeginPoint point = it.next();
					task = point.confirmTask(task);
				}
			}
			if (task == null) return ;
			
			if (task.site.isStop)
				return ;
			
			//扩展点：fetch 获取HTTP内容
			FetchResult result = null;
			Collection<FetchPoint> fetchPoints = task.site.fetchPointImpls;
			if (fetchPoints != null && !fetchPoints.isEmpty()){
				for (Iterator<FetchPoint> it = fetchPoints.iterator(); it.hasNext(); ){
					FetchPoint point = it.next();
					result = point.fetch(task, result);
				}
			}
			
			Page page = result.getPage();
			if (page != null && CommonUtil.isBlank(page.getCharset())) {
				String html = page.getContent();
				if (!CommonUtil.isBlank(html)) {
					html = html.trim().toLowerCase();
					String s1 = CommonUtil.findOneByRegex(html, "(?=<meta ).*charset=.[^/]*");
					if (!CommonUtil.isBlank(s1)) {
						String s2 = CommonUtil.findOneByRegex(s1, "(?=charset\\=).[^;/\"']*");
						if (!CommonUtil.isBlank(s2)) {
							String charset = s2.replace("charset=", "");
							page.setCharset(charset);
							String html2 = null;
							
							try {
								if (Charset.forName(charset) != null) {
								 	html2 = new String(page.getContentData(), charset);
								}
							} catch (Throwable e) {
							}
							if (!CommonUtil.isBlank(html2)) {
								page.setContent(html2);
							}
						}
					}
				}
			}
			
			listener.onFetch(Thread.currentThread(), task, result);
			
			//扩展点：dig new url 发觉新URL
			Collection<String> newUrls = null;
			Collection<DigPoint> digPoints = task.site.digPointImpls;
			if (digPoints != null && !digPoints.isEmpty()){
				for (Iterator<DigPoint> it = digPoints.iterator(); it.hasNext(); ){
					DigPoint point = it.next();
					newUrls = point.digNewUrls(result, task, newUrls);
				}
			}
			
			handleNewUrls(newUrls);
			
			if (page == null) {
				return ;
			}
			
			//扩展点：target 确认是否有目标配置匹配当前URL
			Target target = null;
			Collection<TargetPoint> targetPoints = task.site.targetPointImpls;
			if (targetPoints != null && !targetPoints.isEmpty()){
				for (Iterator<TargetPoint> it = targetPoints.iterator(); it.hasNext(); ){
					TargetPoint point = it.next();
					target = point.confirmTarget(task, target);
				}
			}
			
			if (target == null) {
				return ;
			}
			
			task.target = target;
			task.page = page;
			this.listener.onTargetPage(Thread.currentThread(), task, page);
			
			//检查sourceUrl
			Rules rules = task.site.getTargets().getSourceRules();
			Rule sourceRule = SourceUrlChecker.checkSourceUrl(rules, task.sourceUrl);
			if (sourceRule == null) {
			    listener.onInfo(Thread.currentThread(), task, "target url->"+task.url+"'s source url->"+task.sourceUrl+" is not match the SourceRules");
				return ;
			}
			
			//扩展点：parse 把已确认好的目标页面解析成为Map对象
			List<Map<String, Object>> models = null;
			Collection<ParsePoint> parsePoints = task.site.parsePointImpls;
			if (parsePoints != null && !parsePoints.isEmpty()){
				for (Iterator<ParsePoint> it = parsePoints.iterator(); it.hasNext(); ){
					ParsePoint point = it.next();
					models = point.parse(task, target, page, models);
				}
			}
			
			if (models == null) {
				return ;
			}
			
			for (Iterator<Map<String, Object>> _it = models.iterator(); _it.hasNext(); ){
				 Map<String,Object> model = _it.next();
				 for (Iterator<Field> it = target.getModel().getField().iterator(); it.hasNext(); ){
					 Field f = it.next();
					 //去掉那些被定义成 参数 的field
					 if ("1".equals(f.getIsParam()) || "true".equals(f.getIsParam()))
						 model.remove(f.getName());
				 }
				model.put("source_url", task.sourceUrl);
				model.put("task_url", task.url);
			}
			
			// 统计任务完成数+1
			this.task.site.counter.plus();
			listener.onParse(Thread.currentThread(), task, models);
			
			if (task.digNewUrls != null && !task.digNewUrls.isEmpty()) {
				Set<String> urls = new HashSet<String>(task.digNewUrls.size());
				for (String s : task.digNewUrls){
					if (s == null || s.trim().length() == 0)
						continue;
					
					urls.add(s);
				}
				
				if (!urls.isEmpty()) {
					handleNewUrls(urls);
					task.digNewUrls.clear();
					task.digNewUrls = null;
				}
			}
			
			listener.onInfo(Thread.currentThread(), task, "site -> " + task.site.getName() + " task parse finished count ->" + task.site.counter.getCount());
			
			//扩展点：pojo 将Map数据映射为POJO
			String modelCls = target.getModel().getClazz();
			Class<?> cls = null;
			if (modelCls != null)
				cls = Thread.currentThread().getContextClassLoader().loadClass(modelCls);
			
			List<Object> pojos = null;
			Collection<PojoPoint> pojoPoints = task.site.pojoPointImpls;
			if (pojoPoints != null && !pojoPoints.isEmpty()){
				for (Iterator<PojoPoint> it = pojoPoints.iterator(); it.hasNext(); ){
					PojoPoint point = it.next();
					pojos = point.mapping(task, cls, models, pojos);
				}
			}
			if (pojos != null) 
				listener.onPojo(Thread.currentThread(), task, pojos);
			
			//扩展点：end 蜘蛛完成工作，该收尾了
			Collection<EndPoint> endPoints = task.site.endPointImpls;
			if (endPoints != null && !endPoints.isEmpty()){
				for (Iterator<EndPoint> it = endPoints.iterator(); it.hasNext(); ){
					EndPoint point = it.next();
					models = point.complete(task, models);
				}
			}
			
		} catch (DoneException e){
			if (this.listener != null)
				this.listener.onInfo(Thread.currentThread(), task, "Spiderman has shutdown already...");
		} catch(Throwable e){
			if (this.listener != null)
				this.listener.onError(Thread.currentThread(), task, CommonUtil.getExceptionString(e), e);
		} finally {
			this.listener.onInfo(Thread.currentThread(), task, "spider task done ("+(System.currentTimeMillis()-start)+"ms) \r\n\t" + task.url);
		}
	}

	private void handleNewUrls(Collection<String> newUrls) throws Exception {
		if (newUrls != null && !newUrls.isEmpty())
			this.listener.onNewUrls(Thread.currentThread(), task, newUrls);
		else
			newUrls = new ArrayList<String>();
		
		//扩展点：dup_removal URL去重,然后变成Task
		Collection<Task> validTasks = null;
		Collection<DupRemovalPoint> dupRemovalPoints = task.site.dupRemovalPointImpls;
		if (dupRemovalPoints != null && !dupRemovalPoints.isEmpty()){
			for (Iterator<DupRemovalPoint> it = dupRemovalPoints.iterator(); it.hasNext(); ){
				DupRemovalPoint point = it.next();
				validTasks = point.removeDuplicateTask(task, newUrls, validTasks);
			}
		}
		
		if (newUrls != null && !newUrls.isEmpty())
			this.listener.onDupRemoval(Thread.currentThread(), task, validTasks);
		
		if (validTasks == null)
			validTasks = new ArrayList<Task>();
		
		//扩展点：task_sort 给任务排序
		Collection<TaskSortPoint> taskSortPoints = task.site.taskSortPointImpls;
		if (taskSortPoints != null && !taskSortPoints.isEmpty()){
			for (Iterator<TaskSortPoint> it = taskSortPoints.iterator(); it.hasNext(); ){
				TaskSortPoint point = it.next();
				validTasks = point.sortTasks(validTasks);
			}
		}
		
		this.listener.onTaskSort(Thread.currentThread(), task, validTasks);
		
		if (validTasks == null)
			validTasks = new ArrayList<Task>();
		
		//扩展点：task_push 将任务放入队列
		validTasks = pushTask(validTasks);
		if (validTasks != null && !validTasks.isEmpty()) {
			//将种子信息放入新的任务对象中
			for (Task vt : validTasks) {
				vt.seed = task.seed;
			}
			
			this.listener.onNewTasks(Thread.currentThread(), task, validTasks);
		}
	}

	public Collection<Task> pushTask(Collection<Task> validTasks) throws Exception {
		Collection<TaskPushPoint> taskPushPoints = task.site.taskPushPointImpls;
		if (taskPushPoints != null && !taskPushPoints.isEmpty()){
			for (Iterator<TaskPushPoint> it = taskPushPoints.iterator(); it.hasNext(); ){
				TaskPushPoint point = it.next();
				validTasks = point.pushTask(validTasks);
			}
		}
		return validTasks;
	}

}
