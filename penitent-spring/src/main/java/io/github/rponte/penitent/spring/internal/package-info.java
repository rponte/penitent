/**
 * How {@code PlsqlTransactions} and Spring's transactions keep apart: in a Spring application, Spring
 * owns the transactions, and a {@code PlsqlTransaction} works side by side with them, never inside or
 * around one. Not part of the API; it may change without notice.
 */
package io.github.rponte.penitent.spring.internal;
