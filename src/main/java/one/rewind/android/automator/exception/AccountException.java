package one.rewind.android.automator.exception;

import one.rewind.android.automator.account.Account;

/**
 * @author scisaga@gmail.com
 * @date 2019/1/18
 */
public class AccountException extends Exception {

	/**
	 * 无可用账户异常
	 */
	public static class NoAvailableAccount extends Exception {}

	/**
	 * 账户失效异常
	 */
	public static class Broken extends Exception {

		public Account account;

		public Broken(Account account) {
			this.account = account;
		}

	}


}