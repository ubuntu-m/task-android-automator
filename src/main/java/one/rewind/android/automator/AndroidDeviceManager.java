package one.rewind.android.automator;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import one.rewind.android.automator.account.Account;
import one.rewind.android.automator.adapter.Adapter;
import one.rewind.android.automator.adapter.wechat.WeChatAdapter;
import one.rewind.android.automator.exception.AccountException;
import one.rewind.android.automator.exception.AndroidException;
import one.rewind.android.automator.exception.TaskException;
import one.rewind.android.automator.log.SysLog;
import one.rewind.android.automator.task.Task;
import one.rewind.json.JSON;
import one.rewind.json.JSONable;
import one.rewind.util.NetworkUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * @author maxuefeng[m17793873123@163.com]
 */
public class AndroidDeviceManager {

    private static final Logger logger = LogManager.getLogger(AndroidDeviceManager.class.getName());

    /**
     * 单例
     */
    private static AndroidDeviceManager instance;

    public static AndroidDeviceManager getInstance() {
        synchronized (AndroidDeviceManager.class) {
            if (instance == null) {
                instance = new AndroidDeviceManager();
            }
        }
        return instance;
    }

    // 默认的Device对应的Adapter类的全路径
    public static List<String> DefaultAdapterClassNameList = new ArrayList<>();

    static {
        DefaultAdapterClassNameList.add(WeChatAdapter.class.getName());
    }

    // 所有设备的任务
    public ConcurrentHashMap<AndroidDevice, BlockingQueue<Task>> deviceTaskMap = new ConcurrentHashMap<>();

    public ThreadPoolExecutor executor;

    /**
     *
     */
    private AndroidDeviceManager() {
        executor = new ThreadPoolExecutor(10, 10, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingDeque<>());
        executor.setThreadFactory(new ThreadFactoryBuilder()
                .setNameFormat("AndroidDeviceManager-%d").build());
    }

    /**
     * 初始化设备
     */
    public void initialize() throws Exception {
    }


    public void detectDevices() throws Exception {

        // A 先找设备
        String[] udids = getAvailableDeviceUdids();

        List<AndroidDevice> devices = new ArrayList<>();

        for (String udid : udids) {

            // A1 创建 AndroidDevice 对象
            if (deviceTaskMap.keySet().stream().map(d -> d.udid).collect(Collectors.toList()).contains(udid)) {

                // 此时假设对应Device已经序列化
                logger.info("Device {} already initialized.", udid);

            } else {

                AndroidDevice device = AndroidDevice.getAndroidDeviceByUdid(udid);
                logger.info("udid: " + device.udid);

                // A2 同步数据库对应记录
                device.update();
                devices.add(device);
            }
        }

        // B 加载默认的Adapters
        for (AndroidDevice ad : devices) {

            for (String className : DefaultAdapterClassNameList) {

                Class<?> clazz = Class.forName(className);

                Constructor<?> cons;

                Field[] fields = clazz.getFields();

                boolean needAccount = false;

                for (Field field : fields) {
                    if (field.getName().equals("NeedAccount")) {
                        needAccount = field.getBoolean(clazz);
                        break;
                    }
                }

                // 如果Adapter必须使用Account
                if (needAccount) {

                    cons = clazz.getConstructor(AndroidDevice.class, Account.class);

                    Account account = Account.getAccount(ad.udid, className);

                    if (account != null) {
                        cons.newInstance(ad, account);
                    }
                    // 找不到账号，对应设备无法启动
                    else {

                        SysLog.log("Device [" + ad.udid + "] Add Failed, No available account for " + className);
                        ad.status = AndroidDevice.Status.Failed;
                        ad.update();
                    }

                } else {
                    cons = clazz.getConstructor(AndroidDevice.class);
                    cons.newInstance(ad);
                }
            }

            // 添加到容器中 并添加队列
            deviceTaskMap.put(ad, new LinkedBlockingDeque<>());
            logger.info("add device [{}] in device container", ad.udid);

            // 设备INIT
            ad.start();

            // 添加 idle 回掉方法 获取执行任务
            ad.initCallbacks.add((d) -> {
                assign(d);
            });
        }
    }

    /**
     * 从队列中拿任务
     *
     * @param ad
     * @throws InterruptedException
     * @throws AndroidException.IllegalStatusException
     */
    private void assign(AndroidDevice ad) throws InterruptedException, AndroidException.IllegalStatusException {

        Task task = deviceTaskMap.get(ad.udid).take();
        ad.submit(task);
    }


    /**
     * @param task
     */
    public SubmitInfo submit(Task task) throws AndroidException.NoAvailableDeviceException, TaskException.IllegalParamException, AccountException.AccountNotLoad {

        if (task.holder == null || task.holder.class_name == null) throw new TaskException.IllegalParamException();

        String adapterClassName = task.holder.adapter_class_name;
        if (StringUtils.isNotBlank(adapterClassName)) throw new TaskException.IllegalParamException();

        AndroidDevice device = null;

        // A 指定 account_id
        if (task.holder.account_id != 0) {
            device = deviceTaskMap.keySet().stream()
                    .filter(d -> {
                        Adapter adapter = d.adapters.get(adapterClassName);
                        if (adapter == null) return false;
                        if (adapter.account == null) return false;
                        if (adapter.account.id == task.holder.account_id) return true;
                        return false;
                    })
                    .collect(Collectors.toList())
                    .get(0);

            if (device == null) throw new AccountException.AccountNotLoad();
        }
        // B 指定udid
        else if (task.holder.udid != null) {
            device = deviceTaskMap.keySet().stream()
                    .filter(d -> d.udid.equals(task.holder.udid))
                    .collect(Collectors.toList())
                    .get(0);

            if (device != null && !device.adapters.containsKey(adapterClassName)) device = null;

            if (device == null) throw new AndroidException.NoAvailableDeviceException();
        }
        // C
        else {
            device = getDevice(adapterClassName);
        }

        deviceTaskMap.get(device).offer(task);

        return new SubmitInfo(task, device);
    }

    /**
     * 选择任务最少的Device 保证公平性
     *
     * @param AdapterClassName
     * @return
     */
    public AndroidDevice getDevice(String AdapterClassName) throws AndroidException.NoAvailableDeviceException {


        List<AndroidDevice> devices = deviceTaskMap.keySet().stream()
                .filter(d -> d.status == AndroidDevice.Status.Idle || d.status == AndroidDevice.Status.Busy && d.adapters.get(AdapterClassName) != null)
                .map(d -> new AbstractMap.SimpleEntry<>(d, deviceTaskMap.get(d).size()))
                .sorted(Map.Entry.comparingByValue())
                .limit(1)
                .map(entry -> entry.getKey())
                .collect(Collectors.toList());

        if (devices.size() == 1) {
            return devices.get(0);
        }

        throw new AndroidException.NoAvailableDeviceException();
    }

    /**
     *
     */
    public static class SubmitInfo implements JSONable<SubmitInfo> {

        public boolean success = true;

        String localIp = NetworkUtil.getLocalIp();
        String id;
        String task_class_name;
        int account_id;
        String topic_name;
        AndroidDevice androidDevice;

        /**
         *
         */
        public SubmitInfo() {
        }

        /**
         * @param success
         */
        public SubmitInfo(boolean success) {
            this.success = success;
        }

        /**
         * @param task
         * @param androidDevice
         */
        public SubmitInfo(Task task, AndroidDevice androidDevice) {
            this.id = task.holder.id;
            this.account_id = task.holder.account_id;
            this.task_class_name = task.holder.class_name;
            this.androidDevice = androidDevice;
        }

        @Override
        public String toJSON() {
            return JSON.toJson(this);
        }
    }


    /**
     * 加载数据库中,上一次未完成的任务
     */
	/* public void initMediaStack() {
		Set<String> set = Sets.newHashSet();
		obtainFullData(set, startPage, DeviceUtil.obtainDevices().length);
		mediaStack.addAll(set);
	}


	private void run(WeChatAdapter adapter) {
		try {
			logger.info("start executed");
			//计算任务类型
			adapter.getDevice().taskType = calculateTaskType(adapter);

			System.out.println("当前设备 : " + adapter.getDevice().udid + "的任务类型是: " + adapter.getDevice().taskType);
			//初始化任务队列
			switch (adapter.getDevice().taskType) {
				case Subscribe: {
					distributionSubscribeTask(adapter.getDevice());
					break;
				}
				case Fetch: {
					distributionFetchTask(adapter.getDevice());
					break;
				}
				default:
					logger.info("当前没有匹配到任何任务类型!");
			}
			adapter.start();
		} catch (Exception e) {
			logger.error("初始化任务失败！");
		}
	}


	public void addIdleAdapter(WeChatAdapter adapter) {
		synchronized (this) {
			this.idleAdapters.add(adapter);
		}
	}

	*//**
     * 初始化订阅任务
     *
     * @param device d
     * @throws SQLException e
     *//*
	private void distributionSubscribeTask(AndroidDevice device) throws SQLException {
		device.queue.clear();

		if (mediaStack.isEmpty()) {
			// 如果没有数据了 先初始化订阅的公众号
			startPage += 2;
			initMediaStack();
		}
		// 今日订阅了多少个号
		int numToday = DBUtil.obtainSubscribeNumToday(device.udid);

		System.out.println("今天订阅了" + numToday + "个号");
		// 处于等待状态
		if (numToday > 40) {

			device.status = AndroidDevice.Status.Exceed_Subscribe_Limit;

			device.taskType = null;

		} else {
			RPriorityQueue<String> taskQueue = redisClient.getPriorityQueue(Tab.TOPIC_MEDIA);

			System.out.println("redis中的任务队列的数据是否为空? " + taskQueue.size());

			String redisTask = redisTask(device.udid);

			// 如果可以从redis中加到任务
			if (StringUtils.isNotBlank(redisTask)) {
				device.queue.add(redisTask);
			} else {
				device.queue.add(mediaStack.pop());
			}
		}
	}

	*//**
     * 从redis中加载任务
     *
     * @param originUdid 设备udid标识
     * @return 返回任务
     *//*
	private String redisTask(String originUdid) {
		RPriorityQueue<String> taskQueue = redisClient.getPriorityQueue(Tab.TOPIC_MEDIA);

		for (String var : taskQueue) {
			if (var.contains(Tab.UDID_SUFFIX)) {

				String udid = Tab.udid(var);

				if (!Strings.isNullOrEmpty(udid) && originUdid.equals(udid)) {
					taskQueue.remove(var);
					return var;
				}
			} else {
				taskQueue.remove(var);
				return var;
			}
		}
		return null;
	}


	*//**
     * 从MySQL中初始化任务
     *
     * @param device d
     * @throws SQLException sql e
     *//*
	private void distributionFetchTask(AndroidDevice device) throws SQLException {
		device.queue.clear();
		WechatAccountMediaSubscribe media =
				Tab.subscribeDao.
						queryBuilder().
						where().
						eq("udid", device.udid).
						and().
						eq("status", WechatAccountMediaSubscribe.State.NOT_FINISH.status).
						queryForFirst();

		// 相对于现在没有完成的任务
		if (media == null) {
			device.taskType = null;
			// 处于等待状态
			device.status = AndroidDevice.Status.Exceed_Subscribe_Limit;
			return;
		}
		// 限制初始化一个任务
		device.queue.add(media.media_name);
	}


	*//**
     * 计算任务类型
     *
     * @param adapter
     * @return
     * @throws Exception
     *//*
	private AndroidDevice.Task.Type calculateTaskType(WeChatAdapter adapter) throws Exception {

		String udid = adapter.getDevice().udid;

		long allSubscribe = Tab.subscribeDao.queryBuilder().where().eq("udid", udid).countOf();

		List<WechatAccountMediaSubscribe> notFinishR = Tab.subscribeDao.queryBuilder().where().
				eq("udid", udid).and().
				eq("status", WechatAccountMediaSubscribe.State.NOT_FINISH.status).
				query();

		int todaySubscribe = obtainSubscribeNumToday(udid);

		if (allSubscribe >= 993) {
			if (notFinishR.size() == 0) {
				adapter.getDevice().status = AndroidDevice.Status.Exceed_Subscribe_Limit;
				return null;   //当前设备订阅的公众号已经到上限
			}
			return AndroidDevice.Task.Type.Fetch;
		} else if (todaySubscribe >= 40) {
			if (notFinishR.size() == 0) {
				adapter.getDevice().status = AndroidDevice.Status.Operation_Too_Frequent;
				return null;
			}
			return AndroidDevice.Task.Type.Fetch;
		} else {
			adapter.getDevice().status = null;
			// 当前设备订阅的号没有到达上限则分配订阅任务  有限分配订阅接口任务
			if (notFinishR.size() == 0) {
				return AndroidDevice.Task.Type.Subscribe;
			} else {
				return AndroidDevice.Task.Type.Fetch;
			}
		}
	}

	*//**
     * 计算今日订阅了多少公众号
     *
     * @param udid
     * @return
     * @throws SQLException
     *//*
	private int obtainSubscribeNumToday(String udid) throws SQLException {
		GenericRawResults<String[]> results = Tab.subscribeDao.
				queryRaw("select count(id) as number from wechat_subscribe_account where `status` not in (2) and udid = ? and to_days(insert_time) = to_days(NOW())",
						udid);
		String[] firstResult = results.getFirstResult();
		String var = firstResult[0];
		return Integer.parseInt(var);
	}

	private void reset() {
		try {
			RQueue<Object> taskMedia = redisClient.getQueue(Tab.TOPIC_MEDIA);
			List<WechatAccountMediaSubscribe> accounts = Tab.subscribeDao.queryForAll();
			for (WechatAccountMediaSubscribe v : accounts) {

				if (v.status == 2 && v.number == 0) {
					if (v.topic != null) {
						// 重试
						taskMedia.add(v.media_name + v.topic);
					}
					// 删除记录
					Tab.subscribeDao.delete(v);
				}

				if (v.status == 0) {
					if (v.number >= 100) {
						v.number = v.number * 2;
					} else {
						v.number = 100 * 2;
					}
				}
				v.update();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}


	static class Task implements Callable<Boolean> {
		@Override
		public Boolean call() throws Exception {

			AndroidDeviceManager manager = AndroidDeviceManager.getInstance();

			// 初始化设备
			manager.init();

			// 重置数据库数据
			manager.reset();

			// 初始化
			manager.initMediaStack();

			for (AndroidDevice device : manager.androidDevices) {
				WeChatAdapter adapter = new WeChatAdapter(device);
				adapter.setupDevice();
				manager.idleAdapters.add(adapter);
			}

			do {
				WeChatAdapter adapter = manager.idleAdapters.take();
				// 获取到休闲设备进行任务执行
				manager.run(adapter);
			} while (true);
		}
	}

	// 任务启动入口

	public void run() {
		ListenableFuture<Boolean> result = this.service.submit(new Task());

		Futures.addCallback(result, new FutureCallback<Boolean>() {
			@Override
			public void onSuccess(@NullableDecl Boolean result) {
				logger.info("run success ok!");
			}

			@Override
			public void onFailure(Throwable t) {
				logger.info("run failed Not OK Please focus on this");
			}
		});
	}*/

    /**
     * 获取可用的设备 udid 列表
     *
     * @return
     */
    public static String[] getAvailableDeviceUdids() {

        /*ShellUtil.exeCmd("adb"); // 有可能需要先启动 adb 服务器

        ShellUtil.exeCmd("adb usb"); // 有可能需要刷新 adb udb 连接*/

        BufferedReader br = null;
        StringBuilder sb = new StringBuilder();
        try {

            Process p = Runtime.getRuntime().exec("adb devices");
            br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = null;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            System.out.println(sb.toString());

            logger.info("Console Output info is :[{}]", sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        String r = sb.toString().replace("List of devices attached", "").replace("\t", "");

        return r.split("device");
    }
}






















