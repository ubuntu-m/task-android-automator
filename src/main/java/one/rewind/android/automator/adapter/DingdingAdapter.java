package one.rewind.android.automator.adapter;

import io.appium.java_client.TouchAction;
import io.appium.java_client.android.AndroidElement;
import io.appium.java_client.touch.offset.PointOption;
import one.rewind.android.automator.deivce.AndroidDevice;
import one.rewind.android.automator.account.Account;
import one.rewind.android.automator.exception.AccountException;
import one.rewind.android.automator.exception.AdapterException;
import one.rewind.android.automator.exception.AndroidException;
import one.rewind.db.exception.DBInitException;
import org.openqa.selenium.By;

import java.sql.SQLException;
import java.util.List;

/**
 * 钉钉的自动化操作
 */
public class DingdingAdapter extends Adapter {


	/**
	 * @param androidDevice
	 */
	public DingdingAdapter(AndroidDevice androidDevice) throws AndroidException.IllegalStatusException {
		super(androidDevice);
		this.appInfo = new AppInfo("com.alibaba.android.rimet",
				"com.alibaba.android.rimet.biz.SplashActivity");
	}

	@Override
	public void checkAccount() throws InterruptedException, AdapterException.LoginScriptError, AccountException.Broken {

	}

	@Override
	public void switchAccount(Account.Status... statuses) throws InterruptedException, AdapterException.LoginScriptError, AccountException.NoAvailableAccount, DBInitException, SQLException {

	}


	@Override
	public void switchAccount(Account account) throws InterruptedException, AdapterException.LoginScriptError, AccountException.NoAvailableAccount, SQLException, DBInitException {

	}

	/**
	 * 导出所有钉钉的联系人，半自动
	 *
	 * @throws InterruptedException 中断异常
	 */
	public void getAllDingdingContacts() throws InterruptedException {

		device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'通讯录')]")).click();
		Thread.sleep(500);

		device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'组织架构')]")).click();
		Thread.sleep(200);

		for (int j = 0; j < 50; j++) {

			List<AndroidElement> lis = device.driver.findElementsById("com.alibaba.android.rimet:id/item_contact");

			for (int i = 0; i < lis.size(); i++) {
				lis.get(i).click();
				Thread.sleep(300);
				System.out.println(device.driver.findElementById("com.alibaba.android.rimet:id/cell_subTitle"));
				Thread.sleep(300);
				device.driver.findElementByAccessibilityId("返回");
			}

			TouchAction action = new TouchAction(device.driver)
					.press(PointOption.point(700, 2360))
					.waitAction()
					.moveTo(PointOption.point(700, 200))
					.release();

			action.perform();

			Thread.sleep(400);
		}
	}

	/**
	 * 获取企业信息
	 *
	 * @throws InterruptedException 中断异常
	 */
	public void getDingdingCompanyContact() throws InterruptedException {

		device.driver.findElement(By.xpath("//android.widget.TextView[contains(@text,'通讯录')]")).click();
		Thread.sleep(500);

		device.driver.findElementByAccessibilityId("企业广场").click();
		Thread.sleep(10000);

		System.out.println(device.driver.getContext());
	}
}
