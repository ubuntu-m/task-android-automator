package one.rewind.android.automator.adapter.wechat.task;

import com.dw.ocr.parser.OCRParser;
import com.google.common.collect.ImmutableMap;
import net.lightbody.bmp.filters.RequestFilter;
import net.lightbody.bmp.filters.ResponseFilter;
import one.rewind.android.automator.account.Account;
import one.rewind.android.automator.adapter.Adapter;
import one.rewind.android.automator.adapter.wechat.WeChatAdapter;
import one.rewind.android.automator.adapter.wechat.exception.GetPublicAccountEssayListFrozenException;
import one.rewind.android.automator.adapter.wechat.exception.MediaException;
import one.rewind.android.automator.adapter.wechat.util.Generator;
import one.rewind.android.automator.adapter.wechat.util.PublicAccountInfo;
import one.rewind.android.automator.adapter.wechat.util.ReqObj;
import one.rewind.android.automator.exception.AccountException;
import one.rewind.android.automator.exception.AdapterException;
import one.rewind.android.automator.task.Task;
import one.rewind.android.automator.task.TaskHolder;
import one.rewind.data.raw.model.Comment;
import one.rewind.data.raw.model.Essay;
import one.rewind.data.raw.model.Media;
import one.rewind.data.raw.model.Source;
import one.rewind.db.Daos;
import one.rewind.db.exception.DBInitException;
import one.rewind.db.model.Model;
import one.rewind.txt.ContentCleaner;
import one.rewind.txt.DateFormatUtil;
import one.rewind.txt.NumberFormatUtil;
import one.rewind.txt.StringUtil;
import one.rewind.util.FileUtil;

import java.io.IOException;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * 获取微信公众号文章
 * 构建一个Task需要可用的Device，可用的Adapter，可用的微信账号
 *
 * @author scisaga@gmail.com
 * @date 2019/1/14
 */
public class GetMediaEssaysTask extends Task {

    public String media_nick;

    // 任务对应的Adapter
    public WeChatAdapter adapter;

    public int page = Integer.MAX_VALUE;

    // 采集的公众号
    public Media media;

    // 公众号Essay源代码栈
    public Stack<String> content_stack = new Stack<>();

    // 公众号Essay统计信息源代码栈
    public Stack<String> stats_stack = new Stack<>();

    // 公众号Essay评论源代码栈
    public Stack<String> comments_stack = new Stack<>();

    // 已经访问过的微信公众号文章页面
    public List<String> visitedEssays = new ArrayList<>();

    // 已经保存过的微信公众号文章
    public List<String> collectedEssays = new ArrayList<>();

    public CountDownLatch countDown;

    public volatile boolean forward = false;

    private Map<String, ReqObj> reqs = new HashMap<>();
    private Map<String, Integer> responseCount = new HashMap<>();

    /**
     * @param holder
     * @param params
     * @throws IllegalParamsException
     */
    public GetMediaEssaysTask(TaskHolder holder, String... params) throws IllegalParamsException {

        super(holder, params);

        // A 参数判断 获取需要采集的公众号昵称
		if(params.length == 1) {
			media_nick = params[0];
		} else if(params.length == 2) {
			media_nick = params[0];
			try {
				page = Integer.parseInt(params[1]);
			} catch (Exception e) {
				throw new IllegalParamsException(Arrays.stream(params).collect(Collectors.joining(", ")));
			}
		} else throw new IllegalParamsException(Arrays.stream(params).collect(Collectors.joining(", ")));

        // B 初始化当前任务类型允许的账号状态
        accountPermitStatuses.add(Account.Status.Search_Public_Account_Frozen);

        // C 设定任务完成回调
        addDoneCallback((t) -> {
            // 移除过滤器
            ((GetMediaEssaysTask) t).removeFilters();
        });
    }

    @Override
    public Task setAdapter(Adapter adapter) {
        this.adapter = (WeChatAdapter) adapter;
        return this;
    }

    @Override
    public Adapter getAdapter() {
        return this.adapter;
    }

    @Override
    public Boolean call() throws
			InterruptedException,
			IOException,
			AccountException.NoAvailableAccount,
			AccountException.Broken,
			AdapterException.IllegalStateException,
			AdapterException.NoResponseException,
			AdapterException.LoginScriptError,
			DBInitException,
			SQLException
	{

        // 任务执行
        try {

			RC("判断帐号状态");
			checkAccountStatus(adapter); // 有可能找不到符合条件的账号加载 并抛出NoAvailableAccount异常

			RC("设置过滤器");
            setupFilters();

            RC("重置微信进入首页");
            adapter.restart(); // 由于 checkAccountStatus步骤选择了有效账号，该步骤应该不会抛出Broken异常

			RC("进入已订阅公众号的列表页面");
            adapter.goToSubscribePublicAccountList();

			RC("搜索公众号 " + media_nick);
            adapter.goToSubscribedPublicAccountHome(media_nick);

			RC("查询Media并加载已采集文章 " + media_nick);

            try {

                Media media = Daos.get(Media.class).queryBuilder().where().eq("nick", media_nick).queryForFirst();

                // 如果对应的media不存在
                if (media == null) {

                    media = parseMedia(adapter.getPublicAccountInfo(false, true));
                    media.insert();

                }
                // 加载media已经采集过的文章数据
                else {

					Daos.get(Essay.class).queryBuilder()
                            .where().eq("media_id", media.id)
                            .query()
                            .stream().forEach(essay -> {
                        collectedEssays.add(essay.title + " " + DateFormatUtil.dfd.print(essay.pubdate.getTime()));
                    });
                }

            } catch (SQLException e) {
                logger.error("Error handling DB, ", e);
            } catch (MediaException.Illegal e) {
				failure(e);
				return false;
			}

			RC("进入历史文章数据列表页");
            adapter.goToPublicAccountEssayList();

            boolean atBottom = false;

            int current_page = 0;

            // 是否到达列表底部
            while (!atBottom && current_page < page) {

				RC("- 截图分析文章标题坐标");
                List<OCRParser.TouchableTextArea> textAreas = adapter.getEssayListTextAreas();

				RC("- 获取文章标题区域列表");
                for (OCRParser.TouchableTextArea area : textAreas) {

                    // 通过 textAreas 分析是否是最后一页 一般来讲都是最后一个 是 已无更多
					RC("-- 判定是否已经到达列表底部");
                    if (area.content.equals("已无更多") && textAreas.indexOf(area) == textAreas.size() - 1) {
                        atBottom = true;
                        break;
                    }

					RC("-- 文章标题去重判断");
                    String feature = area.content + " " + DateFormatUtil.dfd.print(area.date.getTime());
                    if (collectedEssays.contains(feature) || visitedEssays.contains(feature)) continue;

					RC("-- 进入文章");
					countDown = new CountDownLatch(1);
                    adapter.goToEssayDetail(area);

					countDown.await(10, TimeUnit.SECONDS);
					RC("-- 判断是否是转发文章");
					if (forward) {

						countDown = new CountDownLatch(1);

						RC("--- 点进被转发的文章");
						adapter.device.touch(582, 557, 6000);

						RC("--- 向下滑动两次");
						for (int i = 0; i < 2; i++) {
							this.adapter.device.slideToPoint(1000, 800, 1000, 2000, 1000);
						}
					}

					RC("-- 关闭文章");
					adapter.goToEssayPreviousPage();
					// adapter.device.touch(67, 165, 1000);

					forward = false;
                }

				RC("- 确定回到文章列表页");
                if (adapter.status != WeChatAdapter.Status.PublicAccount_Essay_List)
                    throw new AdapterException.IllegalStateException(adapter);

				RC("- 向下滑动一页");
                this.adapter.device.slideToPoint(1000, 800, 1000, 2000, 1000);
                current_page ++;
            }

			RC("任务完成");
			success();
            return false;
        }
        // 微信查看全部消息被限流
        catch (GetPublicAccountEssayListFrozenException e) {

            failure(e);

			this.adapter.account.status = Account.Status.Get_Public_Account_Essay_List_Frozen;
			this.adapter.account.update();

            // 将当前任务提交 下一次在执行任务的时候再根据具体帐号状态条件切换帐号
            return true;

        }
        // 在指定账号的订阅列表中找不到指定的公众号的异常
        catch (MediaException.NotSubscribe e) {

			failure(e, e.media_nick + " not subscribe");
			return false;

        }
        // 指定的媒体账号和订阅的账号不一致
        catch (MediaException.NotEqual e) {

			failure(e, "expect:" + e.media_nick_expected + " actual:" + e.media_nick);
			return false;

        } catch (MediaException.Illegal e) {
			failure(e, e.media_nick + " illegal");
			return false;
		}
        /*// 线程中断异常   此异常在外部捕获不到
        catch (InterruptedException e){

            logger.error("Thread Interrupted, ", e);
        }*/
    }

    public static String getFeature(String url) {

    	Map<String, String> features = ImmutableMap.of(
    			"https://mp.weixin.qq.com/mp/profile_ext?action=home", "EssayList",
				"https://mp.weixin.qq.com/mp/profile_ext?action=getmsg", "EssayList",
				"https://mp.weixin.qq.com/s", "EssayContent",
				"https://mp.weixin.qq.com/mp/getappmsgext", "EssayStat",
				"https://mp.weixin.qq.com/mp/appmsg_comment?action=getcomment", "EssayComments"
		);

    	for(String feature : features.keySet()) {
    		if(url.contains(feature)) return features.get(feature);
		}

		return null;
	}

	public static <T> Predicate<T> distinctByKey(Function<? super T, Object> keyExtractor) {
		Map<Object, Boolean> seen = new ConcurrentHashMap<>();
		return object -> seen.putIfAbsent(keyExtractor.apply(object), Boolean.TRUE) == null;
	}

    /**
     *
     */
    public void setupFilters() {

        logger.info("[{}] [{}] Add Request/Response filters...", adapter.getInfo(), getInfo());

        RequestFilter requestFilter = (request, contents, messageInfo) -> {

			// 请求记录
			String url = messageInfo.getOriginalUrl();

			reqs.put(url, new ReqObj(url, request.method(), ImmutableMap.copyOf(request.headers().entries()), contents.getTextContents()));

        	return null;
		};

        ResponseFilter responseFilter = (response, contents, messageInfo) -> {

        	String url = messageInfo.getOriginalUrl();

        	String feature = getFeature(url);

        	// 返回内容记录
			if (contents != null && contents.isText() && feature != null) {

				if(reqs.get(url) != null) {

					int count = responseCount.get(feature) == null? 0 : responseCount.get(feature);

					/*Map<String, String> map = response.headers().entries().stream()
							.collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, toList())))
							.entrySet().stream()
							.map(entry -> new AbstractMap.SimpleEntry<String, String>(entry.getKey(), entry.getValue().stream().collect(Collectors.joining())))
							.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));*/

					ReqObj resObj = reqs.get(url).setRes(
							response.headers().entries().stream()
									.collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, toList())))
							, contents.getTextContents());

					FileUtil.writeBytesToFile(resObj.toJSON().getBytes(), "tmp/wx/res/" + feature + "-"+ (count ++) + ".html");

					responseCount.put(feature, count);
				}
			}

            if (contents != null && (contents.isText() || url.contains("https://mp.weixin.qq.com/s"))) {

                // 正文
                if (url.contains("https://mp.weixin.qq.com/s")) {
                    logger.info(" : " + url);
                    content_stack.push(contents.getTextContents());
                }
                // 统计信息
                else if (url.contains("mp/getappmsgext")) {
                    logger.info(" :: " + url);
                    stats_stack.push(contents.getTextContents());
                }
                // 评论信息
                else if (url.contains("appmsg_comment?action=getcomment")) {
                    logger.info(" ::: " + url);
                    comments_stack.push(contents.getTextContents());
                }

                if (content_stack.size() > 0) {

                    String content_src = content_stack.pop();

                    String url_permanent = null;

                    // TODO 此处模拟共享，复制链接，保存文章持久连接
                    // adapter.device.driver.getClipboardText();

                    // 获取转发的Essay Id
                    String f_id = parseForwardId(content_src);

                    // 判定是否是转发文章
                    if (f_id != null) {
                        forward = true;
                    }

                    Essay essay = null;

                    essay = parseContent(content_src, f_id);

                    try {
                        Source source = new Source(essay.id, url_permanent, null, essay.id + ".html", "text/xml", content_src.getBytes());
                        /*source.insert();*/
                    } catch (Exception e) {
                        logger.error("Error insert source:{}, ", essay.id, e);
                    }

                    essay.source_id = essay.id;
                    essay.origin_url = url_permanent;

                    try {
                        if (stats_stack.size() > 0) {
                            String stats_src = stats_stack.pop();
                            essay = parseStat(essay, stats_src);
                        }
                    } catch (Exception e) {
                        logger.error("Error parse essay:{},", e);
                    }

                    try {
                        essay.insert();
                    } catch (Exception e) {
                        logger.info("Error insert essay:{},", e);
                    }

                    // 对评论的处理
                    if (comments_stack.size() > 0) {

                        String comments_src = comments_stack.pop();

                        List<Comment> comments = null;

                        try {
                            comments = parseComments(Comment.FType.Essay, essay.id, essay.src_id, comments_src);
                        } catch (ParseException e) {
                            logger.error("Error parse comments:{},", e);
                        }

                        comments.stream().forEach(c -> {
                            try {
                                c.insert();
                            } catch (Exception e) {
                                logger.error("Error insert comments:{}", e);
                            }
                        });
                    }


                    if (countDown != null) countDown.countDown();
                }
            }

        };

        adapter.device.setProxyRequestFilter(requestFilter);
        adapter.device.setProxyResponseFilter(responseFilter);
    }

    /**
     *
     */
    public void removeFilters() {

		logger.info("[{}] [{}] Remove Request/Response filters...", adapter.getInfo(), getInfo());

        adapter.device.setProxyRequestFilter((request, contents, messageInfo) -> null);

        adapter.device.setProxyResponseFilter((response, contents, messageInfo) -> {
        });
    }

	/**
	 * 解析文章内容
	 *
	 * @param source
	 * @param f_id
	 * @return
	 */
    public Essay parseContent(String source, String f_id) {

        String title = null;
        String media_nick = null;
        String src_id = null;
        String media_src_id = null;

        // 标题 必须
        Pattern pattern = Pattern.compile("(?si)<h2.*?</h2>|<title>.*?</title>");
        Matcher matcher = pattern.matcher(source);
        if (matcher.find()) {
            title = matcher.group().replaceAll("<.+?>| +|\r\n|\n", "");
        }

        // 公号昵称 必须
        pattern = Pattern.compile("(?si)<strong class=\"profile_nickname\">.+?</strong>|<div class=\"account_nickname\">.+?</div>");
        matcher = pattern.matcher(source);
        if (matcher.find()) {
            media_nick = matcher.group().replaceAll("<.+?>| +|\r\n|\n", "");
        }

        // 原始文章id 必须
        pattern = Pattern.compile("(?si)(?<=mid = \").+?(?=\";)");
        matcher = pattern.matcher(source);
        if (matcher.find()) {
            src_id = matcher.group().replaceAll("\"| |\\|", "");
        }

        // 原始媒体 id
        pattern = Pattern.compile("(?si)(?<=user_name = \").+?(?=\";)");
        matcher = pattern.matcher(source);
        if (matcher.find()) {
            media_src_id = matcher.group().replaceAll("\"| |\\|", "");
        }

        if (title == null || media_nick == null) {
            return null;
        }

        // 生成文章
        Essay essay = new Essay();
        essay.platform_id = WeChatAdapter.platform.id;
        essay.platform = WeChatAdapter.platform.short_name;
        essay.title = title;
        essay.media_nick = media_nick;
        essay.src_id = src_id;
        essay.media_src_id = media_src_id;
        essay.f_id = f_id;
        essay.media_id = Generator.genMediaId(media_nick);
        essay.id = genId(media_nick, title, src_id);

        // 元信息
        pattern = Pattern.compile("(?si)<div id=\"meta_content\" class=\"rich_media_meta_list\">.*?<div id=\"js_profile_qrcode\" class=\"profile_container\" style=\"display:none;\">");
        matcher = pattern.matcher(source);
        if (matcher.find()) {
            essay.meta_content = matcher.group()
                    .replaceAll("<.+?>| +|\r\n|\n", " ")
                    .replaceAll(" +", " ")
                    .replaceAll("： ", "：")
                    .replaceAll("^ ", "")
                    .replaceAll(" $", "");
        }

        // 原始公号name
        // 如果是转发文章，或随手贴个图，就没有这个name
        pattern = Pattern.compile("(?si)<span class=\"profile_meta_value\">.*?</span>");
        matcher = pattern.matcher(source);
        if (matcher.find()) {
            essay.media_name = matcher.group().replaceAll("<.+?>| +|\r\n|\n", "");
        }

		/* pattern = Pattern.compile("(?si)(?<=biz = \").+?(?=\";)");
		matcher = pattern.matcher(source);
		if(matcher.find()) {
			essay.src_media_id = matcher.group().replaceAll("\"| |\\|", "");
		}*/

        // 日期
        pattern = Pattern.compile("(?si)(?<=createDate=new Date\\(\").+?(?=\")");
        matcher = pattern.matcher(source);
        if (matcher.find()) {
            essay.pubdate = DateFormatUtil.parseTime(matcher.group());
        }

		/*long ts = Long.valueOf(this.getStringFromVars("ts")) * 1000L;
		essay.pubdate = new Date(ts);*/

        // 内容
        pattern = Pattern.compile("(?si)(?<=<div class=\"rich_media_content \" id=\"js_content\">).+?(?=</div>)");
        matcher = pattern.matcher(source);
        if (matcher.find()) {

            String content = StringUtil.purgeHTML(matcher.group());

            // 获取 公众号 文章底部的转载信息
            Pattern pattern_copyright = Pattern.compile("(?si)(?<=<a class=\"original_tool_area\" id=\"copyright_info\").+?(?=</a>)");
            Matcher matcher_copyright = pattern_copyright.matcher(source);
            if (matcher_copyright.find()) {

                String copyright = "<p>" + matcher_copyright.group().replaceAll("<.+?>", "") + "</p>";
                content += copyright;
            }

            List<String> imgs = new ArrayList<>();
            essay.content = ContentCleaner.clean(content, imgs);

            // TODO 抓不到头图
			/*if(this.getStringFromVars("cover") != null && this.getStringFromVars("cover").length() > 0) {
				imgs.add(0, this.getStringFromVars("cover"));
			}*/

            // 去重
            imgs = imgs.stream().distinct().collect(toList());

            // 下载图片 保存图片
            List<String> imgs_ = new ArrayList<>();

            for (String img_url : imgs) {

                String img_source_id = StringUtil.MD5(img_url);

                try {
                    // TODO 生成下载图片的任务
                    /*BasicDistributor.getInstance("download").submit(Source.getDTH(img_url));*/
                } catch (Exception | Error e) {
                    logger.error("Error download {}", img_url, e);
                }

                imgs_.add(img_source_id);
                //essay.content = essay.content.replace(img_url, img_source_id); // 不替换url
            }

            // 此时记录的都是id
            //essay.images = imgs_; // 保存原有url
        }

        return essay;
    }

    /**
     * 解析阅读数和点赞量
     *
     * @param essay
     * @param source
     * @return
     */
    public Essay parseStat(Essay essay, String source) {

        Pattern pattern = Pattern.compile("(?si)(?<=\"read_num\":)\\d+");
        Matcher matcher = pattern.matcher(source);
        if (matcher.find()) {
            essay.view_count = NumberFormatUtil.parseInt(matcher.group());
        }

        pattern = Pattern.compile("(?si)(?<=\"like_num\":)\\d+");
        matcher = pattern.matcher(source);
        if (matcher.find()) {
            essay.like_count = NumberFormatUtil.parseInt(matcher.group());
        }

        return essay;
    }

    /**
     * 解析转发文章的id
     *
     * @param content_src
     * @return
     */
    public String parseForwardId(String content_src) {

        String f_id = null;

        // 找转发标题
        String title = null, url_f = null, media_nick = null, src_id = null;

        Pattern pattern = Pattern.compile("(?si)<title>.*?</title>");
        Matcher matcher = pattern.matcher(content_src);

        if (matcher.find()) {
            title = matcher.group().replaceAll("<.+?>| +|\r\n|\n", "");
        }

        // 找转发公众号名称 和 原始文章链接
        pattern = Pattern.compile("(?si)<div class=\"share_media\" id=\"js_share_content\">.*?<img class=\"account_avatar\" .*? alt=\"(?<a>.+?)\">.*?<a id=\"js_share_source\" href=\"(?<s>.+?)\">阅读全文");

        matcher = pattern.matcher(content_src);

        if (matcher.find()) {

            url_f = matcher.group("s")
                    .replaceAll("https?://mp.weixin.qq.com/", "")
                    .replaceAll("&amp;(amp;)?", "&");

            media_nick = matcher.group("a");
        }

        // 原始文章mid
        pattern = Pattern.compile("(?si)(?<=source_mid = \").+?(?=\";)");
        matcher = pattern.matcher(content_src);

        if (matcher.find()) {
            src_id = matcher.group().replaceAll("\"| |\\|", "");
        }

        if (title != null || url_f != null || media_nick != null) {
            f_id = genId(media_nick, title, src_id);
        }

        return f_id;
    }

	/**
	 *
	 * @param f_type
	 * @param f_id
	 * @param mid
	 * @param source
	 * @return
	 * @throws ParseException
	 */
    public static List<Comment> parseComments(Comment.FType f_type, String f_id, String mid, String source) throws ParseException {

        List<Comment> comments = new ArrayList<>();

        source = source.replaceAll("^.+?\"elected_comment\":", "");

        Pattern pattern = Pattern.compile("\\{.+?\"nick_name\":\"(?<nickname>.+?)\",\"logo_url\":\"(?<logourl>.+?)\",\"content\":\"(?<content>.+?)\",\"create_time\":(?<pubdate>.+?),\"content_id\":\"(?<contentid>.+?)\".+?\"like_num\":(?<likecount>.+?),.+?\\}");

        Matcher matcher = pattern.matcher(source);

        while (matcher.find()) {

            Comment comment = new Comment();

            comment.f_type = f_type;
            comment.f_id = f_id;
            comment.src_id = matcher.group("contentid");
            comment.username = matcher.group("nickname");
            // comment.logo_url = matcher.group("logourl").replace("\\", "");
            comment.content = matcher.group("content");
            comment.pubdate = DateFormatUtil.parseTime(matcher.group("pubdate"));
            comment.like_count = NumberFormatUtil.parseInt(matcher.group("likecount"));
            comment.id = StringUtil.MD5(f_type + "::" + f_id + "::" + comment.src_id);

            comments.add(comment);
        }

        return comments;
    }

    /**
     * ID 生成
     *
     * @param media_nick
     * @param title
     * @param src_id
     * @return
     */
    public static String genId(String media_nick, String title, String src_id) {
        return StringUtil.MD5(WeChatAdapter.platform.short_name + "-" + media_nick + "-" + title + "-" + src_id);
    }

	/**
	 *
	 * @param pai
	 * @return
	 * @throws DBInitException
	 * @throws SQLException
	 */
    public static Media parseMedia(PublicAccountInfo pai) throws DBInitException, SQLException {

    	Media media = Model.getById(Media.class, Generator.genMediaId(pai.nick));

    	if(media == null) {
			media = new Media();
			media.id = Generator.genMediaId(pai.nick);
			media.insert();
		}

        media.name = pai.name;
        media.nick = pai.nick;
        media.content = pai.content;
        media.essay_count = pai.essay_count;
        media.subject = pai.subject;
        media.trademark = pai.trademark;
        media.phone = pai.phone;

        return media;
    }

}
