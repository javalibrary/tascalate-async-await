/**
 * ﻿Copyright 2015-2021 Valery Silaev (http://vsilaev.com)
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:

 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.

 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.tascalate.async.examples.bank;

import static net.tascalate.async.CallContext.async;
import static net.tascalate.async.CallContext.await;

import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.CompletionStage;

import net.tascalate.async.async;
import net.tascalate.async.examples.bank.FraudDetectionService.Result;

public class MoneyWithdrawalTask {

	private BankAccount bankAccount;
	
	private FraudDetectionService fraudDetectionService = new FraudDetectionService();
	private AccountTransactionService accountTransactionService = new AccountTransactionService();

	public MoneyWithdrawalTask(final BankAccount bankAccount) {
		this.bankAccount = bankAccount;
	}

	private static String timeStamp(final String s) {
		return new Date() + ": " + s;
	}

	private String formatOutput(final String operation) {
		return "Transfer [" + operation + "] to/from bank account #" + bankAccount.accountNumber;
	}
	
	@async public CompletionStage<String> execute(final BigDecimal amount) throws InterruptedException {
		
		class DemoPrint {
			@async CompletionStage<Integer> go() {
				System.out.println("Inner Class " + amount + " " + MoneyWithdrawalTask.this);
				return async(10);
			}
		}
		
		try {
			await(
				new DemoPrint().go()
			);
			final FraudDetectionService.Result fraudCheckResult = await(fraudDetectionService.checkFraud(bankAccount, amount));
			final BigDecimal currentBalance = await(accountTransactionService.withdraw(bankAccount, amount));
			
			if (fraudCheckResult == Result.DENY) {
				throw new IllegalStateException("Fraud detected");
			}
			return async(timeStamp(formatOutput("withdraw")) + ": success, balance is " + currentBalance);
					
		} catch (final InsufficientFundsException ex) {
			return async(timeStamp(formatOutput("withdraw")) + ": failed, insufficient funds (" + bankAccount.amount + ")"); 
		}
	}
	
}
