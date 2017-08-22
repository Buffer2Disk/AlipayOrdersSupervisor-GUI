package com.wxq.apsv.model;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Timer;
import java.util.stream.Collectors;

import com.wxq.apsv.enums.TaskStatus;
import com.wxq.apsv.interfaces.*;
import com.wxq.apsv.utils.Settings;
import com.wxq.apsv.worker.ApsvTimerTask;
import com.wxq.apsv.worker.ApsvTimerManager;
import org.apache.commons.lang.StringUtils;

/**
 * 任务运行主要数据的模型
 */
public class RunTasksModel implements ObservableSubject {
    private static RunTasksModel instance;

    private ApsvTask currentSelectTask;

    private TaskListModel taskListModel;

    private ArrayList<Observer> observers;

    private ArrayList<ApsvOrder> orders;

    private RunTasksModel(TaskListModel model) {
        this.taskListModel = model;
        this.observers = new ArrayList<>();
        this.orders = new ArrayList<>();

        ArrayList<ApsvTask> tasks = taskListModel.getTasks();
        if (tasks.size() > 0) {
            currentSelectTask = tasks.get(0);
        }
    }

    synchronized public static RunTasksModel getInstance() {
        if (instance == null) {
            instance = new RunTasksModel(TaskListModel.getInstance());
        }
        return instance;
    }

    @Override
    public void RegisterObserver(Observer o) {
        observers.add(o);
        o.Update(this);
    }

    @Override
    public void RemoveObserver(Observer o) {
        observers.remove(o);
    }

    @Override
    public void NotifyAllObservers() {
        for (Observer o:observers) {
            o.Update(this);
        }
    }

    public void SelectTask(String name) {
        Optional<ApsvTask> optional = this.taskListModel.getTasks().stream().filter(t -> StringUtils.equals(t.name, name)).findFirst();
        if (!optional.isPresent()) {
            return;
        }
        ApsvTask task = optional.get();
        if (currentSelectTask == null || task.id != currentSelectTask.id) {
            this.currentSelectTask = task;
            NotifyAllObservers();
        }
    }

    public ApsvTask getCurrentSelectTask() {
        return currentSelectTask;
    }

    public ArrayList<ApsvTask> getTasks() {
        return taskListModel.getTasks();
    }

    public ArrayList<ApsvOrder> getOrders() {
        // 只返回当前选中task的orders
        return orders.stream().filter(o -> o.taskId == currentSelectTask.id).collect(Collectors.toCollection(ArrayList::new));
    }

    synchronized public void AddOrder(ApsvOrder order) {
        orders.removeIf(o -> StringUtils.equals(o.tradeNo, order.tradeNo) && o.taskId == order.taskId);
        orders.add(order);
        if (order.taskId == currentSelectTask.id) {
            NotifyAllObservers();
        }
    }

    public void SwitchTaskStatus() {
        if (currentSelectTask.status == TaskStatus.STOPPED) {
            StartTask();
        } else {
            StopTask();
        }
    }

    //=================================================================
    // #java版存在的问题
    //1.get 请求 cookie 要设置兼容模式(已解决)
    //2.支付宝网页版账单的模式要切换到高级模式，而不是默认的标准版(已解决)
    //
    // #注意事项
    // cookie获取方法 首先访问你的个人支付宝, 进入到https://consumeprod.alipay.com/record/advanced.htm订单列表页面,
    // 使用chrome按F12打开调试工具, 进console选项卡, 输入document.cookie回车, 返回的字符串即为cookies, 复制全部,
    // 不包含包含首尾双引号
    //
    // 没事不要去登录访问网页版的订单界面，当你关闭网页或网页上直接退出或者在网页停留过久无操作可能会触发服务端session更新cookies内容失效
    //=================================================================
    /**
     * 开始当前任务(只会由button UI触发, 肯定为当前选中task)
     */
    public void StartTask() {
        //获取最新的currentTask
        Optional<ApsvTask> op = this.taskListModel.getTasks().stream().filter(t->t.id == currentSelectTask.id).findFirst();
        if(!op.isPresent())
            return;
        currentSelectTask = op.get();

        // 清理当前任务下已抓取的orders
        orders.removeIf(o -> o.taskId == currentSelectTask.id);

        this.taskListModel.MarkTaskStatus(currentSelectTask.id, TaskStatus.RUNNING);
        NotifyAllObservers();


        // 开始抓取定时任务
        Timer timer = new Timer();
        timer.schedule(new ApsvTimerTask(currentSelectTask), 2000, Math.max(Settings.getInstance().getGrapInterval() * 1000, 30000));
        ApsvTimerManager.AddTimer(timer, currentSelectTask.id);
        ApsvTimerManager.RecordStartTime(currentSelectTask.id);
    }

    /**
     * 停止当前任务(只会由button UI触发, 肯定为当前选中task)
     */
    public void StopTask() {
        this.taskListModel.MarkTaskStatus(currentSelectTask.id, TaskStatus.STOPPED);
        NotifyAllObservers();

        // 停止定时抓取任务
        Timer timer = ApsvTimerManager.GetTimer(currentSelectTask.id);
        if (timer != null) {
            timer.cancel();
            timer.purge();
        }
        ApsvTimerManager.ClearStartTime(currentSelectTask.id);
    }

    /**
     * 标记任务异常(由抓取任务线程触发, 不一定为当前选中task)
     */
    public void MarkTaskException(int taskId) {
        this.taskListModel.MarkTaskStatus(taskId, TaskStatus.INERROR);
    }

    /**
     * 停止所有任务(在程序退出的hook中执行)
     */
    public void StopAllTask() {
        ArrayList<ApsvTask> tasks = this.getTasks();
        tasks.forEach(t -> {
            if (t.status != TaskStatus.STOPPED) {
                this.taskListModel.MarkTaskStatus(t.id, TaskStatus.STOPPED);
                Timer timer = ApsvTimerManager.GetTimer(t.id);
                if (timer != null) {
                    timer.cancel();
                    timer.purge();
                }
                ApsvTimerManager.ClearStartTime(t.id);
            }
        });
    }
}
