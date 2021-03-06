package one.rewind.android.automator.test.util;

import com.google.common.collect.Maps;
import one.rewind.android.automator.deivce.AndroidDeviceManager;
import one.rewind.android.automator.util.Tab;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author maxuefeng [m17793873123@163.com]
 */
public class StringTest {

    @Test
    public void testChaineseLength() {
        String tmp = "2018年12月21日";


        System.out.println(tmp.substring(0, 4));

        System.out.println(tmp.substring(5, 7));

//		System.out.println(tmp.substring());
    }

    @Test
    public void testRealMedia() {
        final String media = Tab.realMedia("阿里巴巴Android-Automator-Topic-20190104152236676__udid_ZGHDGSHS");

        System.out.println(media);
    }

    @Test
    public void getDeviceUdid() {
        for (String udid : AndroidDeviceManager.getAvailableDeviceUdids()) {
            System.out.println(udid);
        }

    }

    @Test
    public void testInt2String() {
        int tmp = 2;

        Map<String, Object> map = Maps.newHashMap();

        map.put("account_id", tmp);

        System.out.println(String.valueOf(map.get("account_id")));
    }


    @Test
    public void testSplit() {
        String result = "package:io.appium.settings\n" +
                "package:com.mgyapp.android\n" +
                "package:io.appium.uiautomator2.server\n" +
                "package:com.tencent.mm\n" +
                "package:io.appium.uiautomator2.server.test\n" +
                "package:com.mgyun.shua.protector\n" +
                "package:io.appium.unlock";
        String[] split = result.split("package:");
        List<String> packages = new ArrayList<>();
        for (String var : split) {
            String var0 = var.replaceAll("\n", "");
            if (StringUtils.isNotBlank(var0)) {
                packages.add(var0);
            }
        }

        packages.forEach(System.out::println);
    }

    @Test
    public void testSplit2() {
        String[] result = "ZX1G22MMSQ-9".split("-");
        System.out.println(result[0]);
        System.out.println(result[1]);
    }

    @Test
    public void testStringJoiner() {
        StringJoiner joiner = new StringJoiner("hello").add("asdasd").add("asdsad");
        System.out.println(joiner.toString());
    }

    @Test
    public void testStringUtils() {
        String udid = null;
        if (StringUtils.isNotBlank(udid)) {
            System.out.println("Hello world");
        }
    }

    @Test
    public void testRegex() {


        String regex = "(?<=\\[)\\d条(?=\\])";

        String var = "[1条] [图片]";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(var);

        if (matcher.find()) {
            System.out.println(matcher.group().replace("条", ""));
        }

        if (var.matches(regex)) System.out.println("hello world111");
    }

}
