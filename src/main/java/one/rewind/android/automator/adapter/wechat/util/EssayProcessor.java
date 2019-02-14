package one.rewind.android.automator.adapter.wechat.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import one.rewind.android.automator.adapter.wechat.WeChatAdapter;
import one.rewind.data.raw.model.Comment;
import one.rewind.data.raw.model.Essay;
import one.rewind.db.exception.DBInitException;
import one.rewind.db.model.Model;
import one.rewind.io.requester.basic.Cookies;
import one.rewind.io.requester.chrome.ChromeTask;
import one.rewind.io.requester.task.Task;
import one.rewind.io.requester.task.TaskHolder;
import one.rewind.txt.*;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * @author scisaga@gmail.com
 * @date 2019/2/14
 */
public class EssayProcessor {

	public static final Logger logger = LogManager.getLogger(EssayProcessor.class.getName());

	/**
	 * EssayProcessor 所有生成的任务对应的 url --> 关联 EssayProcessor 对象
	 */
	public static ConcurrentHashMap<String, EssayProcessor> holderProcessorMap = new ConcurrentHashMap<>();

	/**
	 * 微信公众号历史文章页面的第一个列表请求
	 */
	public ReqObj list0;

	/**
	 * 微信公众号历史文章页面的第一个翻页请求
	 */
	public ReqObj list1;

	/**
	 * 公用 Header
	 */
	public Map<String, String> headers;

	/**
	 * 公用 cookie store
	 */
	public Cookies.Store cookieStore;

	/**
	 * 构造方法
	 * @param list0 微信公众号历史文章页面的第一个列表请求
	 * @param list1 微信公众号历史文章页面的第一个翻页请求
	 * @throws Exception
	 */
	public EssayProcessor(ReqObj list0, ReqObj list1) throws Exception {

		this.list0 = list0;

		// 初始化 cookie store
		cookieStore = list0.getCookies();

		this.list1 = list1;

		// 更新 cookie store
		cookieStore.add(list1.getCookies());

		// 初始化 公用 header
		headers = list1.headers;

		// TODO 生成后续翻页任务

	}

	/**
	 * 从list1..N 中 生成翻页任务
	 * @param url
	 * @param src
	 * @return
	 * @throws Exception
	 */
	public void getNextPageTH(String url, String src, List<TaskHolder> nths) throws Exception {

		// 是否有可翻页标识
		if (canMsgContinue(src)) {

			// 获取下一个翻页的url
			int offset = Integer.parseInt(URLUtil.getParam(url, "offset"));

			// 生成下一页 翻页请求
			String newUrl = url.replaceAll("offset=" + offset, "offset=" + (offset + 10));

			TaskHolder th = ChromeTask.at(ListTask.class, ImmutableMap.of("url", newUrl));
			holderProcessorMap.put(th.id, this);

			nths.add(th);
		}
	}

	/**
	 * 列表页是否可翻页
	 * @param src
	 * @return
	 */
	public static boolean canMsgContinue(String src) {

		if(src.contains("\"can_msg_continue\":1")) return true;
		return false;
	}

	/**
	 * 从 listX 内容中 生成 文章采集任务
	 *
	 * @param src
	 * @return
	 */
	public void getEssayTH(String src, String nick, List<TaskHolder> nths) throws Exception {

		src = StringEscapeUtils.unescapeHtml4(src).replaceAll("\\\\", "").replaceAll("^.+?\"general_msg_list\":\"", "").replaceAll("\",\"next_offset\":20.+?$", "");

		// 构造 JsonNode
		ObjectMapper mapper = new ObjectMapper();
		JsonNode jsonNode = mapper.readTree(src);

		// 解析 list 属性对应的 元素
		Iterator<JsonNode> elements = jsonNode.get("list").elements();

		while(elements.hasNext()){

			JsonNode item = elements.next();

			// 获取公众号昵称
			String media_nick = item.get("app_msg_ext_info").get("author").asText();
			if(media_nick.length() == 0) media_nick = nick;

			// 获取文章标题
			String title = item.get("app_msg_ext_info").get("title").asText();

			// 获取文章时间标识 注意：此时间还不是文章的真正发布时间
			String datetime = item.get("comm_msg_info").get("datetime").asText();

			// 获取头图链接
			String cover = item.get("app_msg_ext_info").get("cover").asText();

			// 获取文章链接
			String new_url = item.get("app_msg_ext_info").get("content_url").asText();

			// 生成第一层级的文章采集任务
			if(new_url != null && new_url.length() > 1) {

				logger.info("title:{} media_nick:{} ts:{} url:{} cover:{}", title, media_nick, datetime, new_url, cover);

				TaskHolder th = ChromeTask.at(
						EssayTask.class,
						ImmutableMap.of(
								"url", new_url,
								"title", title,
								"media_nick", media_nick,
								"ts", datetime,
								"cover", cover
						));

				holderProcessorMap.put(th.id, this);

				nths.add(th);
			}

			// app_msg_ext_info.multi_app_msg_item_list 还会包含文章信息
			Iterator<JsonNode> inner_elements = item.get("app_msg_ext_info").get("multi_app_msg_item_list").elements();

			while(inner_elements.hasNext()) {

				JsonNode inner_item = inner_elements.next();

				// 公众号昵称
				String inner_media_nick = inner_item.get("author").asText();
				if(inner_media_nick.length() == 0) inner_media_nick = nick;

				// 文章标题
				String inner_title = inner_item.get("title").asText();

				// 文章链接
				String inner_url = inner_item.get("content_url").asText();

				// 头图
				String inner_cover = inner_item.get("cover").asText();

				if(inner_url != null && inner_url.length() > 1) {

					logger.info("title:{} media_nick:{} ts:{} url:{} cover:{}", inner_title, inner_media_nick, datetime, inner_url, inner_cover);

					TaskHolder th_ = ChromeTask.at(
							EssayTask.class,
							ImmutableMap.of(
									"url", inner_url,
									"title", inner_title,
									"media_nick", inner_media_nick,
									"ts", datetime,
									"cover", inner_cover
							));

					holderProcessorMap.put(th_.id, this);

					nths.add(th_);
				}
			}
		}
	}

	/**
	 * 解析转发文章的id
	 *
	 * @param src
	 * @return
	 */
	public String parseFid(String src, List<TaskHolder> nths, String cover) throws Exception {

		String f_id = null, title = null, url_f = null, media_nick = null, src_id = null;

		// 找转发标题
		Pattern pattern = Pattern.compile("(?si)<title>.*?</title>");
		Matcher matcher = pattern.matcher(src);

		if (matcher.find()) {
			title = matcher.group().replaceAll("<.+?>| +|\r\n|\n", "");
		}

		// 找转发公众号名称 和 原始文章链接
		pattern = Pattern.compile("(?si)<div class=\"share_media\" id=\"js_share_content\">.*?<img class=\"account_avatar\" .*? alt=\"(?<a>.+?)\">.*?<a id=\"js_share_source\" href=\"(?<s>.+?)\">阅读全文");

		matcher = pattern.matcher(src);

		if (matcher.find()) {

			url_f = matcher.group("s")
					.replaceAll("https?://mp.weixin.qq.com/", "")
					.replaceAll("&amp;(amp;)?", "&");

			media_nick = matcher.group("a");
		}

		// 解析原始文章mid
		pattern = Pattern.compile("(?si)(?<=source_mid = \").+?(?=\";)");
		matcher = pattern.matcher(src);

		if (matcher.find()) {
			src_id = matcher.group().replaceAll("\"| |\\|", "");
		}

		// 生成原始文章id
		if (title != null && url_f != null && media_nick != null) {
			f_id = Generator.genEssayId(media_nick, title, src_id);

			logger.info("title:{} media_nick:{} url:{} cover:{}", title, media_nick, url_f, cover);

			TaskHolder th_ = ChromeTask.at(
					EssayTask.class,
					ImmutableMap.of(
							"url", url_f,
							"title", title,
							"media_nick", media_nick,
							"cover", cover == null? "" : cover
					));

			holderProcessorMap.put(th_.id, this);
			nths.add(th_);
		}

		return f_id;
	}

	/**
	 * 解析文章内容
	 *
	 * @param source
	 * @param f_id
	 * @return
	 */
	public static Essay parseContent(String source, String f_id, String cover_image) {

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
		essay.id = Generator.genEssayId(media_nick, title, src_id);

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

			// 头图
			if(cover_image!= null && cover_image.length() > 0) {
				imgs.add(0, cover_image);
			}

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
				//essay.content = essay.content.replace(img_url, img_source_id); // 保存原有url
			}

			// 此时记录的都是id
			//essay.images = imgs_; // 保存原有url
		}

		return essay;
	}

	/**
	 *
	 * @param src
	 * @param essay_id
	 * @param title
	 * @param biz
	 * @param abtest_cookie
	 * @param pass_ticket
	 * @param nths
	 * @throws Exception
	 */
	public void getStatTH(String src, String essay_id, String title, String biz, String abtest_cookie, String pass_ticket, List<TaskHolder> nths) throws Exception {

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("essay_id", essay_id);
		data.put("pass_ticket_e3", pass_ticket == null? "" : encode(pass_ticket, 3));
		data.put("biz_e1", biz == null? "" : encode(biz, 1));
		data.put("title_e2", title == null? "" : encode(title, 2));
		data.put("abtest_cookie_e1", abtest_cookie == null? "" : encode(abtest_cookie, 1));

		Map<String, String> patterns = new LinkedHashMap<>();
		patterns.put("abtest_cookie", "abtest_cookie.+?\"(?<T>.+?)\"");
		patterns.put("uin", "window.uin.+?\"(?<T>\\d+?)\"");
		patterns.put("key", "window.key.+?\"(?<T>\\d+?)\"");
		patterns.put("wxtoken", "window.wxtoken.+?\"(?<T>\\d+?)\"");
		patterns.put("devicetype", "devicetype.+?\"(?<T>.+?)\"");
		patterns.put("clientversion", "clientversion.+?\"(?<T>.+?)\"");
		patterns.put("appmsg_token", "appmsg_token.+?\"(?<T>.+?)\"");
		patterns.put("mid", "mid.+?\"(?<T>\\d+?)\"");
		patterns.put("sn", "sn.+?\"(?<T>.+?)\"");
		patterns.put("ct", "ct.+?\"(?<T>\\d+?)\"");
		patterns.put("comment_id", "comment_id.+?\"(?<T>\\d+?)\"");
		patterns.put("req_id", "req_id.+?\"(?<T>.+?)\"");

		for(String k : patterns.keySet()) {
			Pattern p = Pattern.compile(patterns.get(k));
			Matcher m = p.matcher(src);
			if(m.find()) {
				data.put(k, m.group("T"));
			}
		}

		/*for(String k : patterns.keySet()) {
			Pattern p = Pattern.compile(patterns.get(k));
			Matcher m = p.matcher(src);
			if(m.find()) {
				if(k.equals("abtest_cookie_e1")) {
					data.put(k, encode(m.group("T"), 1));
				} else data.put(k, m.group("T"));
			}
		}
		System.err.println(JSON.toPrettyJson(data));*/

		TaskHolder th = ChromeTask.at(StatTask.class, data);
		holderProcessorMap.put(th.id, this);
		nths.add(th);
	}

	/**
	 *
	 * @param src
	 * @param biz
	 * @param pass_ticket
	 * @param nths
	 * @throws Exception
	 */
	public void getCommentTH(String src, String essay_id, String biz, String pass_ticket, List<TaskHolder> nths) throws Exception {

		Map<String, Object> data = new HashMap<>();
		data.put("essay_id", essay_id);
		data.put("biz_e1", biz == null? "" : encode(biz, 1));
		data.put("pass_ticket_e3", pass_ticket == null? "" : encode(pass_ticket, 3));

		Map<String, String> patterns = new HashMap<>();
		patterns.put("appmsgid", "mid.+?\"(?<T>\\d+)\"");
		patterns.put("comment_id", "comment_id.+?\"(?<T>\\d+)\"");
		patterns.put("uin", "window.uin.+?\"(?<T>\\d+)\".*?;");
		patterns.put("key", "window.key.+?\"(?<T>\\d+)\".*?;");
		patterns.put("wxtoken", "window.wxtoken.+?\"(?<T>\\d+)\".*?;");
		patterns.put("devicetype", "devicetype.+?\"(?<T>.+)\"");
		patterns.put("clientversion", "clientversion.+?\"(?<T>.+)\"");
		patterns.put("appmsg_token", "appmsg_token.+?\"(?<T>.+)\"");

		for(String k : patterns.keySet()) {
			Pattern p = Pattern.compile(patterns.get(k));
			Matcher m = p.matcher(src);
			if(m.find()) {
				data.put(k, m.group("T"));
			}
		}

		TaskHolder th = ChromeTask.at(CommentTask.class, data);
		holderProcessorMap.put(th.id, this);
		nths.add(th);
	}

	/**
	 * 解析阅读数和点赞量
	 *
	 * @param essay
	 * @param source
	 * @return
	 */
	public static Essay parseStat(Essay essay, String source) {

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
	 *
	 * @param f_type
	 * @param f_id
	 * @param src
	 * @return
	 * @throws ParseException
	 */
	public static List<Comment> parseComments(Comment.FType f_type, String f_id, String src) {

		List<Comment> comments = new ArrayList<>();

		src = src.replaceAll("^.+?\"elected_comment\":", "");

		Pattern pattern = Pattern.compile("\\{.+?\"nick_name\":\"(?<nickname>.+?)\",\"logo_url\":\"(?<logourl>.+?)\",\"content\":\"(?<content>.+?)\",\"create_time\":(?<pubdate>.+?),\"content_id\":\"(?<contentid>.+?)\".+?\"like_num\":(?<likecount>.+?),.+?\\}");

		Matcher matcher = pattern.matcher(src);

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
			comment.id = Generator.genCommentId(f_id, comment.src_id, comment.content);

			comments.add(comment);
		}

		return comments;
	}

	/**
	 * 分页内容解析任务
	 */
	public static class ListTask extends ChromeTask {

		static {

			/**
			 * 注册 获取列表 任务
			 */
			ChromeTask.registerBuilder(
					ListTask.class,
					"{{url}}",
					ImmutableMap.of("url", "String", "media_nick", "String"),
					ImmutableMap.of("url", "", "media_nick", ""),
					false,
					Priority.LOW,
					0 // 不去重
			);
		}

		EssayProcessor ep;

		public ListTask(String url, TaskHolder holder) throws MalformedURLException, URISyntaxException {

			super(url);

			ep = holderProcessorMap.get(holder.id);
			if(ep != null) {
				setHeaders(ep.headers);
				setCookies(ep.cookieStore.getCookies(url));
			}

			addNextTaskGenerator((t, nts) -> {

				// 生成文章采集任务
				ep.getEssayTH(t.getResponse().getText(), getStringFromVars("media_nick"), nts);

				// 生成翻页任务
				ep.getNextPageTH(t.url, t.getResponse().getText(), nts);

				if(t.getResponse().getCookies() != null)
					ep.cookieStore.add(t.getResponse().getCookies());

			});
		}
	}

	/**
	 * 文章采集任务
	 */
	public static class EssayTask extends ChromeTask {

		static {

			/**
			 * 注册 解析文章内容 任务
			 */
			ChromeTask.registerBuilder(
					EssayTask.class,
					"{{url}}",
					ImmutableMap.of("url", "String", "title", "String", "media_nick", "String", "ts", "String", "cover", "String"),
					ImmutableMap.of("url", "", "title", "", "media_nick", "", "ts", "", "cover", ""),
					false,
					Task.Priority.MEDIUM,
					60 * 60 * 1000L
			);
		}

		EssayProcessor ep;

		public EssayTask(String url, TaskHolder holder) throws MalformedURLException, URISyntaxException {

			super(url);

			ep = holderProcessorMap.get(holder.id);
			if(ep != null) {
				setHeaders(ep.headers);
				setCookies(ep.cookieStore.getCookies(url));
			}

			addNextTaskGenerator((t, nts) -> {

				String f_id = ep.parseFid(t.getResponse().getText(), nts, t.getStringFromVars("cover"));

				Essay essay = ep.parseContent(t.getResponse().getText(), f_id, t.getStringFromVars("cover"));
				essay.upsert();

				ep.getStatTH(t.getResponse().getText(), essay.id,
						getStringFromVars("title"),
						URLUtil.getParam(t.url, "__biz"),
						URLUtil.getParam(t.url, "abtest_cookie"),
						ep.cookieStore.getCookies(t.url, "pass_ticket"), nts);

				ep.getCommentTH(t.getResponse().getText(), essay.id,
						URLUtil.getParam(t.url, "__biz"),
						ep.cookieStore.getCookies(t.url, "pass_ticket"), nts);


				if(t.getResponse().getCookies() != null)
					ep.cookieStore.add(t.getResponse().getCookies());

			});
		}
	}

	/**
	 *
	 */
	public static class StatTask extends ChromeTask {

		static {

			/**
			 * 注册 获取文章统计信息 任务
			 */
			Map<String, String> paramTypes1 = Maps.newHashMap();
			paramTypes1.put("uin", "String");
			paramTypes1.put("key", "String");
			paramTypes1.put("pass_ticket_e3", "String");
			paramTypes1.put("wxtoken", "String");
			paramTypes1.put("devicetype", "String");
			paramTypes1.put("clientversion", "String");
			paramTypes1.put("appmsg_token", "String");
			paramTypes1.put("biz_e1", "String");
			paramTypes1.put("mid", "String");
			paramTypes1.put("sn", "String");
			paramTypes1.put("title_e2", "String");
			paramTypes1.put("ct", "String");
			paramTypes1.put("abtest_cookie_e1", "String");
			paramTypes1.put("comment_id", "String");
			paramTypes1.put("req_id", "String");
			paramTypes1.put("essay_id", "String");

			Map<String, Object> paramDefaults1 = paramTypes1.entrySet().stream().map(en -> new AbstractMap.SimpleEntry<>(en.getKey(), "")).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

			ChromeTask.registerBuilder(
					StatTask.class,
					"https://mp.weixin.qq.com/mp/getappmsgext?f=json&mock=&uin={{uin}}&key={{key}}&pass_ticket={{pass_ticket_e3}}&wxtoken={{wxtoken}}&devicetype={{devicetype}}&clientversion={{clientversion}}&appmsg_token={{appmsg_token}}&x5=1&f=json",
					"r=0.8186501018503087&__biz={{biz_e1}}&appmsg_type=9&mid={{mid}}&sn={{sn}}&idx=1&scene=4&title={{title_e2}}&ct={{ct}}&abtest_cookie={{abtest_cookie_e1}}&devicetype={{devicetype}}&version={{clientversion}}&is_need_ticket=0&is_need_ad=0&comment_id={{comment_id}}&is_need_reward=0&both_ad=0&reward_uin_count=0&send_time=&msg_daily_idx=1&is_original=0&is_only_read=1&req_id={{req_id}}&pass_ticket={{pass_ticket_e3}}&is_temp_url=0&item_show_type=0&tmp_version=1&more_read_type=0&appmsg_like_type=2",
					paramTypes1,
					paramDefaults1,
					false,
					Task.Priority.HIGH,
					60 * 60 * 1000L
			);
		}

		EssayProcessor ep;

		public StatTask(String url, TaskHolder holder) throws MalformedURLException, URISyntaxException {

			super(url);

			ep = holderProcessorMap.get(holder.id);
			if(ep != null) {
				Map<String, String> newHeader = new HashMap<>(ep.headers);
				newHeader.put("Content-Length", "123"); // TODO
				newHeader.put("Origin", "https://mp.weixin.qq.com");
				setHeaders(newHeader);
				setCookies(ep.cookieStore.getCookies(url));
			}

			addNextTaskGenerator((t, nts) -> {

				Essay essay = Model.getById(Essay.class, getStringFromVars("essay_id"));
				if(essay != null) {
					ep.parseStat(essay, t.getResponse().getText());
					essay.update();
				}

				if(t.getResponse().getCookies() != null)
					ep.cookieStore.add(t.getResponse().getCookies());

			});

		}
	}

	/**
	 *
	 */
	public static class CommentTask extends ChromeTask {

		static {

			/**
			 * 注册 获取文章评论 任务
			 */
			Map<String, String> paramTypes2 = Maps.newHashMap();
			paramTypes2.put("biz_e1", "String");
			paramTypes2.put("appmsgid", "String");
			paramTypes2.put("comment_id", "String");
			paramTypes2.put("uin", "String");
			paramTypes2.put("key", "String");
			paramTypes2.put("pass_ticket_e3", "String");
			paramTypes2.put("wxtoken", "String");
			paramTypes2.put("devicetype", "String");
			paramTypes2.put("clientversion", "String");
			paramTypes2.put("appmsg_token", "String");
			paramTypes2.put("essay_id", "String");

			Map<String, Object> paramDefaults2 = paramTypes2.entrySet().stream().map(en -> new AbstractMap.SimpleEntry<>(en.getKey(), "")).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

			ChromeTask.registerBuilder(
					CommentTask.class,
					"https://mp.weixin.qq.com/mp/appmsg_comment?action=getcomment&scene=0&__biz={{biz_e1}}&appmsgid={{appmsgid}}&idx=1&comment_id={{comment_id}}&offset=0&limit=100&uin={{uin}}&key={{key}}&pass_ticket={{pass_ticket_e3}}&wxtoken={{wxtoken}}&devicetype={{devicetype}}&clientversion={{clientversion}}&appmsg_token={{appmsg_token}}&x5=1&f=json",
					paramTypes2,
					paramDefaults2,
					false,
					Task.Priority.HIGH,
					60 * 60 * 1000L
			);
		}

		EssayProcessor ep;

		public CommentTask(String url, TaskHolder holder) throws MalformedURLException, URISyntaxException {

			super(url);

			ep = holderProcessorMap.get(holder.id);
			if(ep != null) {
				setHeaders(ep.headers);
				setCookies(ep.cookieStore.getCookies(url));
			}

			addNextTaskGenerator((t, nts) -> {

				ep.parseComments(Comment.FType.Essay, getStringFromVars("essay_id"), t.getResponse().getText())
					.forEach(comment -> {
						try {
							comment.insert();
						} catch (DBInitException | SQLException e) {
							logger.error("Error insert comment, ", e);
						}
					});

				if(t.getResponse().getCookies() != null)
					ep.cookieStore.add(t.getResponse().getCookies());
			});
		}
	}

	/**
	 *
	 * @param src
	 * @param count
	 * @return
	 * @throws UnsupportedEncodingException
	 */
	public static String encode(String src, int count) throws UnsupportedEncodingException {
		if (count > 0) {
			return encode(URLEncoder.encode(src, "UTF-8"), -- count);
		}
		return src;
	}

	public static String parseHtml(String src) {

		Pattern pattern = Pattern.compile("(?<=var msgList = ').+?(?=';)");
		Matcher matcher = pattern.matcher(src);

		if(matcher.find()) {
			src = matcher.group();
		}

		return StringEscapeUtils.unescapeHtml4(src).replaceAll("\\\\", "");
	}
}
