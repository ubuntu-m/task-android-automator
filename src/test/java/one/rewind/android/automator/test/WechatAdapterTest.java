package one.rewind.android.automator.test;

import net.lightbody.bmp.filters.RequestFilter;
import net.lightbody.bmp.filters.ResponseFilter;
import one.rewind.android.automator.AndroidDevice;
import one.rewind.android.automator.AndroidDeviceManager;
import one.rewind.android.automator.adapter.WechatAdapter;
import one.rewind.android.automator.exception.AndroidCollapseException;
import one.rewind.android.automator.exception.InvokingBaiduAPIException;
import one.rewind.android.automator.model.Comments;
import one.rewind.android.automator.model.Essays;
import one.rewind.android.automator.util.AndroidUtil;
import one.rewind.android.automator.util.AppInfo;
import one.rewind.android.automator.util.MD5Util;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.Stack;
import java.util.concurrent.ArrayBlockingQueue;

public class WechatAdapterTest {

    //    String udid = "ZX1G323GNB";
//    String udid = "ZX1G22PQLH";
    String udid = "1115fb232c991003";
    int appiumPort = 47454;
    int localProxyPort = 48454;
    AndroidDevice device;
    WechatAdapter adapter;

    /**
     * 初始化设备
     *
     * @throws Exception
     */
    @Before
    public void setup() throws Exception {

        device = new AndroidDevice(udid, appiumPort);

        //device.removeWifiProxy();
        device.startProxy(localProxyPort);
        device.setupWifiProxy();

        /**
         * TODO 请求过滤器
         */
        RequestFilter requestFilter = (request, contents, messageInfo) -> {

            String url = messageInfo.getOriginalUrl();

            if (url.contains("https://mp.weixin.qq.com/s"))
                System.out.println(" . " + url);

            return null;
        };

        Stack<String> content_stack = new Stack<>();
        Stack<String> stats_stack = new Stack<>();
        Stack<String> comments_stack = new Stack<>();

        /**
         * TODO 返回过滤器
         */
        ResponseFilter responseFilter = (response, contents, messageInfo) -> {

            String url = messageInfo.getOriginalUrl();

            if (contents != null && (contents.isText() || url.contains("https://mp.weixin.qq.com/s"))) {

                try {
                    // 正文
                    if (url.contains("https://mp.weixin.qq.com/s")) {
                        device.setClickEffect(true);
                        System.err.println(" : " + url);
                        content_stack.push(contents.getTextContents());
                    }
                    // 统计信息
                    else if (url.contains("getappmsgext")) {
                        device.setClickEffect(true);
                        System.err.println(" :: " + url);
                        stats_stack.push(contents.getTextContents());
                    }
                    // 评论信息
                    else if (url.contains("appmsg_comment?action=getcomment")) {
                        device.setClickEffect(true);
                        System.err.println(" ::: " + url);
                        comments_stack.push(contents.getTextContents());
                    }

                    if (content_stack.size() > 0) {
                        device.setClickEffect(true);
                        System.out.println("有内容了");
                        String content_src = content_stack.pop();
                        Essays we;
                        if (stats_stack.size() > 0) {
                            String stats_src = stats_stack.pop();
                            we = new Essays().parseContent(content_src).parseStat(stats_src);
                        } else {
                            we = new Essays().parseContent(content_src);
                            we.view_count = 0;
                            we.like_count = 0;
                        }
                        we.id = MD5Util.MD5Encode("WX" + we.media_name + we.title, "UTF-8");
                        we.insert_time = new Date();

                        we.update_time = new Date();

                        we.media_content = we.media_nick;
                        we.platform = "WX";
                        we.platform_id = 1;
                        we.fav_count = 0;
                        we.forward_count = 0;
                        we.insert();
                        if (comments_stack.size() > 0) {
                            String comments_src = comments_stack.pop();
                            List<Comments> comments_ = Comments.parseComments(we.src_id, comments_src);
                            comments_.stream().forEach(c -> {
                                try {
                                    c.insert();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        };

        device.setProxyRequestFilter(requestFilter);
        device.setProxyResponseFilter(responseFilter);

        // 从AppInfo中选择需要启动的程序
        AppInfo appInfo = AppInfo.get(AppInfo.Defaults.WeChat);

        device.initAppiumServiceAndDriver(appInfo);

        adapter = new WechatAdapter(device);

        Thread.sleep(3000);
    }

    //先将公众号关注  再点击进去抓取文章

    @Test
    public void testGetOnePublicAccountsEssays() throws
            AndroidCollapseException, InvokingBaiduAPIException {
        adapter.getIntoPublicAccountEssayList("阿里巴巴", false);
    }

    @Test
    public void testGetOnePublicAccountsEssaysByHandlerException() {
        adapter.digestionCrawler("民间股神007", false);
    }

    @Test
    public void testActiveApp() throws InterruptedException {
        device.driver.closeApp();
        AndroidUtil.activeWechat(device);
        AndroidUtil.enterEssaysPage("Java技术栈", device.driver);
    }


    @Test
    public void testSubscribeAccount() throws Exception {
        Queue<String> queue = new ArrayBlockingQueue<>(10);
        queue.add("菜鸟教程");
        queue.add("淘迷网");
        for (String var : queue) {
            adapter.subscribeWxAccount(var);
        }
    }


    @Test
    public void testCloseAPP() {

//		device.stop();
//		device.driver.close();

//		device.driver.manage().ime().deactivate();

//		device.driver.terminateApp(udid);

//		device.driver.closeApp();

//		Route postAccounts = PublicAccountsHandler.postAccounts;

    }


    @Test
    public void testAllotTask() throws InterruptedException {
        AndroidDeviceManager.originalAccounts.add("菜鸟教程");
        AndroidDeviceManager.originalAccounts.add("Java技术栈");
        AndroidDeviceManager manager = AndroidDeviceManager.getInstance();
        manager.allotTask(AndroidDeviceManager.TaskType.SUBSCRIBE);

    }

}
