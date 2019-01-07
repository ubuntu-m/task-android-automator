package one.rewind.android.automator.adapter;

import com.google.common.collect.Sets;
import io.appium.java_client.TouchAction;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.touch.offset.PointOption;
import joptsimple.internal.Strings;
import net.lightbody.bmp.filters.RequestFilter;
import net.lightbody.bmp.filters.ResponseFilter;
import one.rewind.android.automator.AndroidDevice;
import one.rewind.android.automator.exception.AlreadySubscribeException;
import one.rewind.android.automator.exception.AndroidCollapseException;
import one.rewind.android.automator.exception.InvokingBaiduAPIException;
import one.rewind.android.automator.exception.SearchMediaException;
import one.rewind.android.automator.model.*;
import one.rewind.android.automator.util.*;
import one.rewind.db.RedissonAdapter;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.redisson.api.RPriorityQueue;
import org.redisson.api.RedissonClient;

import java.io.File;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.*;

/**
 * 采集某个号时  先初始化缓存数据  如果从DB中加载数据会降低效率
 *
 * @author maxuefeng[m17793873123@163.com]
 */
public abstract class AbstractWeChatAdapter extends Adapter {

	ThreadLocal<Boolean> lastPage = new ThreadLocal<>();

	ThreadLocal<Boolean> firstPage = new ThreadLocal<>();


	/**
	 * 上一次分析点击坐标记录的集合  每次执行新任务会将此集合进行清空   利用回调机制实现
	 */
	Set<String> currentTitles = Sets.newHashSet();

	private ThreadLocal<Integer> countVal = new ThreadLocal<>();

	@Deprecated
	void setCountVal() {
		if (countVal.get() != null) {
			int var = countVal.get();
			var += 1;
			countVal.set(var);
		} else {
			countVal.set(1);
		}
	}


	public static final int RETRY_COUNT = 20;

	AbstractWeChatAdapter(AndroidDevice device) {
		super(device);
	}

	@Deprecated
	private WordsPoint accuracySubscribe(String mediaName) throws Exception {

		String fileName = UUID.randomUUID().toString() + ".png";

		String path = System.getProperty("user.dir") + "/screen/";

		screenshot(fileName, path, device.driver);

		JSONObject jsonObject = TesseractOCRAdapter.imageOcr(path + fileName, false);

		FileUtil.deleteFile(path + fileName);

		JSONArray result = jsonObject.getJSONArray("words_result");
		int top;
		int left;
		int i = 0;
		for (Object v : result) {


			JSONObject b = (JSONObject) v;
			String words = b.getString("words");
			if (words.startsWith("(")) words = words.replace("(", "");
			if (words.startsWith(")")) words = words.replace(")", "");
			words = words.replaceAll(" ", "");

			JSONObject location = b.getJSONObject("location");
			top = location.getInt("top");
			left = location.getInt("left");

			// 第一个成为正确的几率最大
			// 很有可能是公众号的头像中的文字也被识别了    去除前三个JSON数据，第三个数据也是此处的第一个数据，第一条数据命中率针对于mediaName是最高的

			if (i == 0) {
				if (words.endsWith(mediaName)) {
					return new WordsPoint(top + 30, left + 30, 0, 0, words);
				}
			}
			if (left <= 50 && words.endsWith(mediaName)) {
				return new WordsPoint(top + 30, left + 30, 0, 0, words);
			}
			if (words.equalsIgnoreCase(mediaName) || words.equalsIgnoreCase("<" + mediaName)) {
				return new WordsPoint(top + 30, left + 30, 0, 0, words);
			}
			i++;
		}
		return null;
	}

	/**
	 * 获取可点击的点
	 *
	 * @return 返回坐标集合
	 * @throws Exception 抛出AndroidException
	 */
	private List<WordsPoint> obtainClickPoints() throws Exception {

		String filePrefix = UUID.randomUUID().toString();
		String fileName = filePrefix + ".png";
		String path = System.getProperty("user.dir") + "/screen/";

		logger.info("截图文件路径为: {}", path + fileName);

		screenshot(fileName, path, device.driver);
		// 图像分析   截图完成之后需要去掉头部的截图信息  头部包括一些数据
		return analysisImage(path + fileName);
	}

	/**
	 * 分析图像  得到的做坐标集合是一个按照时间
	 *
	 * @param filePath 文件路径
	 * @return 返回坐标集合
	 */
	private List<WordsPoint> analysisImage(String filePath) throws Exception {

		JSONObject origin = TesseractOCRAdapter.imageOcr(filePath, true);

		// TODO 删除文件放到ocr adapter上做
		try {
			// 删除图片文件
			FileUtil.deleteFile(filePath);

			// 删除html文件
			FileUtil.deleteFile(filePath.replace(".png", ".hocr"));

		} catch (Exception e) {
			e.printStackTrace();
		}
		List<WordsPoint> result = analysisWordsPoint(origin.getJSONArray("words_result"));

		// 定位最新任务
//		if (this.relativeFlag.history) {
//
//			SimpleDateFormat df = new SimpleDateFormat("yyyy年MM月dd日");
//
//			for (WordsPoint point : result) {
//				// 对于point处理一下  "2018年09月09日 原创"
//				String words = point.words;
//
//				if (words.length() >= 11) {
//
//					// 11代表取前十一个字符[0,11) 取不到第十一个字符  保证得到数据的标准格式是yyyy年MM月dd日
//					words = words.substring(0, 11);
//
//					// 首先比较relativeFlag的record字段是否相等于words
//					if (words.equals(this.relativeFlag.record)) {
//						// 标记任务结束
//						lastPage.set(Boolean.TRUE);
//						//
//						int index = result.indexOf(point);
//
//						for (int i = index + 1; i <= result.size(); i++) {
//							result.remove(i);
//						}
//						// 回调函数  形成闭环
//						this.relativeFlag.callback();
//						return result;
//					}
//				}
//				// 如果因为安卓惯性造成无法对接上一次的记录(需要对于时间的大小进行表)
//				Date d1 = df.parse(words);
//
//				// 或者终止条件按照日期去推断,能保证程序不会继续向下无限走
//				Date d2 = df.parse(this.relativeFlag.record);
//
//				if (d2.compareTo(d1) <= 0) {
//					// TODO  效率优化  去掉重复的坐标点  获取到当前的坐标点  去掉当前坐标数据后面的数据 reduce
//					lastPage.set(Boolean.TRUE);
//
//					int index = result.indexOf(point);
//					for (int i = index + 1; i <= result.size(); i++) {
//						result.remove(i);
//					}
//					this.relativeFlag.callback();
//					return result;
//				}
//			}
//		}
		return result;
	}

	/**
	 * {"words":"My Bastis三种批量插入方式的性能","location":{"top":1305,"left":42,"width":932,"height":78}}
	 *
	 * @param array 图像识别出来的结果
	 * @return 分析得出的坐标位置
	 */
	private List<WordsPoint> analysisWordsPoint(JSONArray array) throws AndroidCollapseException {

		int count = 0;

		int differenceCount = 0;

		JSONArray tmpArray = array;

		List<WordsPoint> wordsPoints = new ArrayList<>();

		//计算坐标  文章的标题最多有两行  标题过长微信会使用省略号代替掉
		for (int i = 0; i < array.length(); i++) {

			JSONObject outJSON = (JSONObject) array.get(i);

			JSONObject inJSON = outJSON.getJSONObject("location");

			String words = outJSON.getString("words");

			// 置换为空的提高效率操作
			if (Strings.isNullOrEmpty(words)) continue;

			if (currentTitles.contains(words)) {

				differenceCount += 1;

				boolean flag = true;

				int k = i + 1;

				while (flag) {

					if (k > array.length() - 1) break;

					// 如果存在重复记录   删除下一条坐标信息
					// JSONArray由于逻辑问题不能删除任何元素  将words可以替换
					JSONObject tmpJSON = (JSONObject) array.get(k);

					String tmpWords1 = tmpJSON.getString("words");

					if (tmpWords1.contains("年") && tmpWords1.contains("月") && (tmpWords1.contains("曰") || tmpWords1.contains("日"))) {
						count++;
						flag = false;
					}
					// 将内容置换为空字符串  防止在统计坐标时出现重复
					tmpJSON.put("words", "");

					array.put(k, tmpJSON);

					k++;
				}
				continue;
			}

			if (words.contains("微信没有响应") || words.contains("全部消息")) throw new AndroidCollapseException("微信没有响应！");

			// TODO  判断当前的索引是数组中的最后一个
			if (words.contains("已无更多") || words.contains("己无更多")) {

				logger.info("==============翻到最后一页=============");

				lastPage.set(Boolean.TRUE);

				return wordsPoints;
			}

			int left = inJSON.getInt("left");

			//确保时间标签的位置   有可能有年月日字符串的在文章标题中   为了防止这种情况   left<=80

			if (words.contains("年") && words.contains("月") && left <= 80 && (words.contains("曰") || words.contains("日"))) {

				count++;

				int top = inJSON.getInt("top");

				int width = inJSON.getInt("width");

				int height = inJSON.getInt("height");

				wordsPoints.add(new WordsPoint((top), left, width, height, words));

				if (wordsPoints.size() >= 6) return wordsPoints;
			}
		}

		// TODO 统计到最后一页
		if (differenceCount != 0 && differenceCount == currentTitles.size() && count > 0) {
			lastPage.set(Boolean.TRUE);
		}

		previousTitles(tmpArray);

		logger.info("count :  {}", count);

		logger.info("wordsPoints size: " + wordsPoints.size());  // wordsPoints的size位0的时候,直接向下翻一页

		// TODO 抛出异常固然不能提高运行效率 但是可以解决问题  更好的解决方案是判断当前页面在那个位置  根据不同的位置进行不同的调整 可以大大提高采集效率
		if (count < 1) throw new AndroidCollapseException("未知异常!没有检测到任务文章数据!");

		return wordsPoints;
	}


	/**
	 * 记录上一次的图像识别的结果
	 *
	 * @param array 当前的文章标题
	 */
	private void previousTitles(JSONArray array) {

		for (int i = 0; i < array.length(); i++) {

			JSONObject tmpJSON = (JSONObject) array.get(i);

			String words = tmpJSON.getString("words");

			if (!words.contains("年") && !words.contains("月") && !(words.contains("曰") || words.contains("日"))) {
				if (i != array.length() - 1) {
					currentTitles.add(words);
				}
			}
		}
	}

	/**
	 * @param mediaName 公众号名称
	 * @param retry     是否重试
	 * @throws Exception e
	 */
	private void delegateOpenEssay(String mediaName, boolean retry) throws Exception {
		if (retry) {
			if (!restore(mediaName)) {
				return;
			}
		} else {

			slideToPoint(431, 1250, 431, 600, device.driver, 1000);

			firstPage.set(Boolean.FALSE);
			// 采集近期更新的文章,出发lastPage的条件是接着上一次更新的时间
			SubscribeMedia subscribeRecord = Tab.subscribeDao.queryBuilder().where().eq("udid", device.udid).and().eq("media_name", mediaName).queryForFirst();
			// 搜索不到数据
			if (subscribeRecord == null) {
				lastPage.set(Boolean.TRUE);
				return;
			}
		}

		while (!lastPage.get()) {

			// 翻到下一页
			if (!firstPage.get()) {
				// 第一次
				slideToPoint(606, 2387, 606, 300, device.driver, 2000);
				firstPage.set(Boolean.FALSE);
			}

			List<WordsPoint> wordsPoints = obtainClickPoints();

			if (wordsPoints.size() == 0) {

				if (!lastPage.get()) {
					// TODO   很大程度的造成微信无故被关闭  很可能是因为图像识别的缘故或者安卓惯性造成(效率优化的一块存在)
					logger.info("图像识别的结果为空!");
				} else {
					logger.info("公众号{}抓取到最后一页了", mediaName);
				}

			} else {
				openEssays(wordsPoints);
			}
		}
	}


	private boolean restore(String mediaName) {
		try {
			long count = Tab.essayDao.queryBuilder().where().eq("media_nick", mediaName).countOf();
			this.firstPage.set(count == 0);
			if (!this.firstPage.get()) {

				slideToPoint(431, 1250, 431, 455, device.driver, 1000);

				int var = (int) count % 6;

				int slideNumByPage;

				if (var == 0) {
					slideNumByPage = (int) ((count / 6) + 2);
				} else if (var <= 3) {
					slideNumByPage = (int) (count / 6) + 1;
				} else {
					slideNumByPage = (int) (count / 6) + 2;
				}
				for (int i = 0; i < slideNumByPage; i++) {
					slideToPoint(606, 2387, 606, 960, device.driver, 1500);
				}
			}
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * @param wordsPoints 每一篇文章的坐标点集合
	 * @throws AndroidCollapseException 安卓崩溃
	 * @throws InterruptedException     线程中断
	 */
	private void openEssays(List<WordsPoint> wordsPoints) throws AndroidCollapseException, InterruptedException {
		int neverClickCount = 0;
		for (WordsPoint wordsPoint : wordsPoints) {

			// 点击不动卡主抛出安卓设备异常
			if (neverClickCount > 3) {
				throw new AndroidCollapseException("安卓系统卡住点不动了！");
			}
			clickPoint(320, wordsPoint.top, 8000, device.driver);
			//所以去判断下是否点击成功    成功：返回上一页面   失败：不返回上一页面  continue
			if (this.device.isClickEffect()) {

				System.out.println("文章点进去了....");
				for (int i = 0; i < 2; i++) {
					slideToPoint(1413, 2369, 1413, 277, device.driver, 500);
				}

				Thread.sleep(1000);

				//关闭文章
				AndroidUtil.closeEssay(device.driver);

				//设置为默认值
				this.device.setClickEffect(false);
			} else {
				++neverClickCount;
			}

		}
	}

	/**
	 * 截图
	 *
	 * @param fileName
	 * @param path
	 */
	public static void screenshot(String fileName, String path, AndroidDriver driver) {
		try {
			File screenFile = ((TakesScreenshot) driver)
					.getScreenshotAs(OutputType.FILE);

			FileUtils.copyFile(screenFile, new File(path + fileName));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 点击固定的位置
	 *
	 * @param xOffset
	 * @param yOffset
	 * @param sleepTime 睡眠时间
	 * @throws InterruptedException
	 */
	public static void clickPoint(int xOffset, int yOffset, int sleepTime, AndroidDriver driver) throws InterruptedException {
		new TouchAction(driver).tap(PointOption.point(xOffset, yOffset)).perform();
		if (sleepTime > 0) {
			Thread.sleep(sleepTime);
		}
	}


	@Deprecated
	public void unsubscribeMedia(String mediaName) {
		try {
			device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'通讯录')]")).click();

			Thread.sleep(1000);

			device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'公众号')]")).click();

			Thread.sleep(1000);

			device.driver.findElement(By.xpath("//android.widget.ImageButton[contains(@content-desc,'搜索')]")).click();

			Thread.sleep(500);

			// 搜索
			device.driver.findElement(By.className("android.widget.EditText")).sendKeys(mediaName);

			clickPoint(720, 150, 1000, device.driver);
			clickPoint(1350, 2250, 1000, device.driver);

			// 进入公众号
			clickPoint(720, 360, 1000, device.driver);

			device.driver.findElement(By.xpath("//android.widget.ImageButton[contains(@content-desc,'聊天信息')]")).click();

			Thread.sleep(1000);

			device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'取消关注')]")).click();
			Thread.sleep(1000);

			device.driver.findElement(By.xpath("//android.widget.Button[contains(@text,'不再关注')]")).click();
			Thread.sleep(1000);
		} catch (Exception e) {
			e.printStackTrace();
			device.driver.navigate().back();
			device.driver.navigate().back();
		}
		device.driver.navigate().back();
	}


	/**
	 * 订阅公众号
	 * <p>
	 * 要订阅的公众号可能存在一个问题就是搜索不到微信账号或者最准确的结果并不是第一个
	 *
	 * @param mediaName
	 * @throws Exception
	 */
	public void subscribeMedia(String mediaName) throws AlreadySubscribeException, SearchMediaException, InterruptedException, SQLException {

		// 获取topic
		String topic = Tab.topic(mediaName);

		mediaName = Tab.realMedia(mediaName);

		if (Tab.subscribeDao.queryBuilder().where().eq("media_name", mediaName).countOf() >= 1) return;

		//重启
		AndroidUtil.restartWechat(device);

		Thread.sleep(3000);

		// A 点搜索
		device.driver.findElement(By.xpath("//android.widget.TextView[contains(@content-desc,'搜索')]")).click();
		Thread.sleep(5000);

		// B 点公众号
		device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'公众号')]")).click();
		Thread.sleep(5000);

		// C1 输入框输入搜索信息
		device.driver.findElement(By.className("android.widget.EditText")).sendKeys("\"" + mediaName + "\"");
		Thread.sleep(3000);

		// C3 点击软键盘的搜索键
		clickPoint(1350, 2250, 6000, device.driver); //TODO

		// 点击第一个位置
		clickPoint(320, 406, 2000, device.driver);

		// 点击订阅
		try {
			device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'关注公众号')]")).click();
			Thread.sleep(4000);
			saveSubscribeRecord(mediaName, topic);

		} catch (NoSuchElementException e) {
			e.printStackTrace();
			logger.error("查找不到当前公众号!");
			throw new AlreadySubscribeException("可能是已经订阅了当前公众号");
		}

	}

	private void saveSubscribeRecord(String mediaName, String topic) {
		try {
			long tempCount = Tab.subscribeDao.queryBuilder().where()
					.eq("media_name", mediaName)
					.countOf();
			if (tempCount == 0) {
				SubscribeMedia e = new SubscribeMedia();
				e.udid = device.udid;
				e.media_name = mediaName;
				e.number = 100;
				e.retry_count = 0;
				e.status = SubscribeMedia.State.NOT_FINISH.status;
				e.request_id = topic;
				e.relative = 1;
				e.insert();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * @param mediaName
	 * @throws InterruptedException
	 */
	public static boolean enterEssay(String mediaName, AndroidDevice device) throws Exception {

		try {

			device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'通讯录')]")).click();

		} catch (Exception e) {
			e.printStackTrace();
			AndroidUtil.closeApp(device);
			AndroidUtil.activeWechat(device);
			device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'通讯录')]")).click();
		}
		Thread.sleep(1000);

		device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'公众号')]")).click();

		Thread.sleep(1000);

		device.driver.findElement(By.xpath("//android.widget.ImageButton[contains(@content-desc,'搜索')]")).click();

		Thread.sleep(1000);

		// 搜索
		device.driver.findElement(By.className("android.widget.EditText")).sendKeys(mediaName);

		clickPoint(720, 150, 1000, device.driver);

		clickPoint(1350, 2250, 1000, device.driver);
		try {
			// 进入公众号
			clickPoint(720, 360, 1000, device.driver);

			device.driver.findElement(By.xpath("//android.widget.ImageButton[contains(@content-desc,'聊天信息')]")).click();

			Thread.sleep(1000);

			slideToPoint(720, 1196, 720, 170, device.driver, 1000);

			device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'全部消息')]")).click();

			Thread.sleep(12000); // TODO 此处时间需要调整
			return true;
		} catch (Exception e) {
			try {
				SubscribeMedia var = Tab.subscribeDao.queryBuilder().where().eq("udid", device.udid).and().eq("media_name", mediaName).queryForFirst();
				if (var != null) {
					var.update_time = new Date();
					var.status = SubscribeMedia.State.NOT_EXIST.status;
					var.update();
				}
			} catch (Exception e1) {
				e1.printStackTrace();
			}
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * 下滑到指定位置
	 */
	private static void slideToPoint(int startX, int startY, int endX, int endY, AndroidDriver driver, int sleepTime) throws InterruptedException {
		new TouchAction(driver).press(PointOption.point(startX, startY))
				.waitAction()
				.moveTo(PointOption.point(endX, endY))
				.release()
				.perform();
		Thread.sleep(sleepTime);
	}


	/**
	 * 重试
	 *
	 * @param mediaName 公众号名称
	 * @param udid      手机udid
	 * @return 为空
	 * @throws Exception ex
	 */
	public static SubscribeMedia retry(String mediaName, String udid) throws Exception {
		SubscribeMedia media = Tab.subscribeDao.queryBuilder().where().eq("media_name", mediaName).and().eq("udid", udid).queryForFirst();
		if (media == null) {
			media = new SubscribeMedia();
			media.update_time = new Date();
			media.insert_time = new Date();
			media.status = SubscribeMedia.State.NOT_EXIST.status;
			media.media_name = mediaName;
			media.udid = udid;
			media.number = 0;
			media.retry_count = 0;
			media.insert();
			return null;
		}

		if (media.status == SubscribeMedia.State.NOT_EXIST.status) {
			media.retry_count = 0;
			media.update_time = new Date();
			media.number = 0;
			media.update();
			return null;
		}
		long count = Tab.essayDao.queryBuilder().where().eq("media_nick", mediaName).countOf();
		if (media.retry_count >= RETRY_COUNT) {
			media.update_time = new Date();
			media.number = (int) count;
			media.status = SubscribeMedia.State.FINISH.status;
			media.update();
			return null;
		}
		if (count < media.number) {
			return media;
		} else {
			return null;
		}
	}

	public void digestionCrawler(String mediaName, boolean retry) {
		try {
			if (!enterEssay(mediaName, device)) {
				lastPage.set(Boolean.TRUE);
				for (int i = 0; i < 3; i++) {
					device.driver.navigate().back();
				}
				return;
			}
			delegateOpenEssay(mediaName, retry);
		} catch (Exception e) {

			e.printStackTrace();

			logger.error(e);

			device.clearCacheLog();
			device.clearAllLog();

			if (e instanceof AndroidCollapseException) {
				logger.error("设备{}链路出问题了.", device.udid);
				try {
					AndroidUtil.closeApp(device);

					AndroidUtil.activeWechat(this.device);
					SubscribeMedia media = retry(mediaName, this.device.udid);
					if (media != null) {
						media.retry_count += 1;
						media.update_time = new Date();
						media.update();
						if (media.retry_count >= RETRY_COUNT) lastPage.set(Boolean.TRUE);
					}

				} catch (Exception e1) {
					logger.error(e1);
				}
			} else if (e instanceof InvokingBaiduAPIException) {

				e.printStackTrace();
				logger.error("百度API调用失败！");
				try {
					// 需要计算啥时候到达明天   到达明天的时候需要重新分配任务
					Date nextDay = DateUtil.buildDate();

					Date thisDay = new Date();

					long waitMills = Math.abs(nextDay.getTime() - thisDay.getTime());

					Thread.sleep(waitMills + 1000 * 60 * 5);

				} catch (Exception e2) {
					logger.error(e2);
				}
			} else if (e instanceof InterruptedException) {
				e.printStackTrace();
				logger.error("InterruptedException 线程中断异常！");
			} else if (e instanceof NoSuchElementException) {
				// 如果搜索不到公众号，则会在此处形成死循环  其他没有捕获到的异常

				e.printStackTrace();
			} else {
				lastPage.set(Boolean.TRUE);
			}
		}
	}

	/**
	 * @param mediaName 公众号名称  可能包含topic和udid
	 */
	public void digestionSubscribe(String mediaName) {
		try {
			subscribeMedia(mediaName);
		} catch (Exception e) {
			logger.error("失败原因如下");

			e.printStackTrace();
			if (e instanceof AlreadySubscribeException) {
				logger.info("失败原因是已经订阅了当前公众号; 设备:{};公众号:{}", device.udid, Tab.realMedia(mediaName));

				try {
					// 返回到主界面即可
					for (int i = 0; i < 3; i++) {
						device.driver.navigate().back();
						Thread.sleep(1000);
					}
				} catch (InterruptedException e1) {
					logger.error(e1);
				}

			} else if (e instanceof SearchMediaException) {
				logger.info("失败原因是搜索出现问题;  设备:{};公众号:{}", device.udid, Tab.realMedia(mediaName));

				device.clearCacheLog();

				device.clearAllLog();

				try {
					// 截图关闭
					AndroidUtil.closeApp(device);

					AndroidUtil.restartWechat(device);

					// 是否是接口任务
					if (mediaName.contains(Tab.REQUEST_ID_SUFFIX)) {
						// 将数据添加到redis中保证数据没有丢失  形成一个闭环
						RedissonClient client = RedissonAdapter.redisson;

						RPriorityQueue<Object> topicMedia = client.getPriorityQueue(Tab.TOPIC_MEDIA);
						if (!topicMedia.contains(mediaName)) {
							topicMedia.add(mediaName);
						}
					}
				} catch (Exception e1) {
					logger.error(e1);
				}
			} else if (e instanceof InterruptedException) {
				logger.error("线程中断错误!!当前线程名称:{}", Thread.currentThread().getName());
				e.printStackTrace();
			} else if (e instanceof SQLException) {
				logger.error("SQL数据库操作异常!");
				e.printStackTrace();
			} else if (e instanceof NoSuchElementException) {
				try {
					logger.info("失败原因是NoSuchElementException");
					AndroidUtil.closeApp(device);
					AndroidUtil.restartWechat(device);
				} catch (InterruptedException ignore) {
					logger.error(ignore);
				}
			}

		}
	}


	/**
	 * 睡眠策略
	 */
	@Deprecated
	private void sleepPolicy() {
		try {
			if (this.countVal.get() != null) {

				//  抓取50篇文章休息3分钟
				Integer var = countVal.get();
				if (var % 50 == 0) {
					Thread.sleep(1000 * 60 * 3);
					// 不推荐的做法
					sleep(1000 * 60 * 3);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Deprecated
	private void sleep(long millis) throws InterruptedException {
		// 手机睡眠
		// 线程睡眠
		Thread.sleep(millis);
		// 手机唤醒
//        ShellUtil.notifyDevice(device.udid, device.driver);
	}

	abstract void start();

	abstract void stop();

	// 启动Device
	public void startUpDevice() {
		Optional.of(this.device).ifPresent(t -> {
			t.startProxy(t.localProxyPort);
			t.setupWifiProxy();
			logger.info("Starting....Please wait!");
			try {

				RequestFilter requestFilter = (request, contents, messageInfo) -> null;

				Stack<String> content_stack = new Stack<>();
				Stack<String> stats_stack = new Stack<>();
				Stack<String> comments_stack = new Stack<>();

				ResponseFilter responseFilter = (response, contents, messageInfo) -> {

					String url = messageInfo.getOriginalUrl();

					if (contents != null && (contents.isText() || url.contains("https://mp.weixin.qq.com/s"))) {

						// 正文
						if (url.contains("https://mp.weixin.qq.com/s")) {
							t.setClickEffect(true);
							/*System.err.println(" : " + url);*/
							content_stack.push(contents.getTextContents());
						}
						// 统计信息
						else if (url.contains("getappmsgext")) {
							t.setClickEffect(true);
							/*System.err.println(" :: " + url);*/
							stats_stack.push(contents.getTextContents());
						}
						// 评论信息
						else if (url.contains("appmsg_comment?action=getcomment")) {
							t.setClickEffect(true);
							/*System.err.println(" ::: " + url);*/
							comments_stack.push(contents.getTextContents());
						}

						if (content_stack.size() > 0) {

							t.setClickEffect(true);
							String content_src = content_stack.pop();
							Essays we = null;
							try {
								if (stats_stack.size() > 0) {
									String stats_src = stats_stack.pop();
									we = new Essays().parseContent(content_src).parseStat(stats_src);
								} else {
									we = new Essays().parseContent(content_src);
									we.view_count = 0;
									we.like_count = 0;
								}
							} catch (Exception e) {
								logger.error("文章解析失败！", e);
							}

							assert we != null;

							we.insert_time = new Date();
							we.update_time = new Date();
							we.media_content = we.media_nick;
							we.platform = "WX";
							we.media_id = MD5Util.MD5Encode(we.platform + "-" + we.media_name, "UTF-8");
							we.platform_id = 1;
							we.fav_count = 0;
							we.forward_count = 0;
							we.images = new JSONArray(we.parseImages(we.content)).toString();
							we.id = MD5Util.MD5Encode(we.platform + "-" + we.media_name + we.title, "UTF-8");

							try {
								we.insert();
							} catch (Exception ignored) {

							}
							if (comments_stack.size() > 0) {
								String comments_src = comments_stack.pop();
								List<Comments> comments_ = null;
								try {
									comments_ = Comments.parseComments(we.src_id, comments_src);
								} catch (ParseException e) {
									logger.error("----------------------");
								}
								comments_.stream().forEach(c -> {
									try {
										c.insert();
									} catch (Exception e) {
										logger.error("----------------评论插入失败！重复key----------------");
									}
								});
							}
						}
					}
				};
				t.setProxyRequestFilter(requestFilter);
				t.setProxyResponseFilter(responseFilter);
				// 启动device
				t.startAsync();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}
}
