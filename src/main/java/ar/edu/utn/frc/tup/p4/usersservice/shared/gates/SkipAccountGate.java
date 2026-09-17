package ar.edu.utn.frc.tup.p4.usersservice.shared.gates;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * DEC-14 - gates are account-status conditions, not role conditions. Putting
 * them in @PreAuthorize would repeat the same condition in every annotation and
 * require every controller to know the statuses.
 *
 * <p><b>Why it lives in the base rather than in the batch that implements it.</b>
 * Three batches WRITE the annotation and only one INTERPRETS it:
 *
 * <ul>
 *   <li>L4 (T8) writes the {@code AccountGateInterceptor}, which reads the
 *       annotation and decides. That logic still belongs to L4.</li>
 *   <li>L1 (T14, T15) puts it on logout, refresh and password change.</li>
 *   <li>L6 puts it on the onboarding endpoints.</li>
 * </ul>
 *
 * An annotation that three batches need in order to COMPILE is a seam, and
 * seams belong to the base by definition. While it lived inside T8, L1 could
 * not write a line of T14 until L4 merged: two batches from different waves
 * serialized by a six-line file.
 *
 * <p><b>Without L4's interceptor this exempts nothing, and that is fine.</b> It
 * is a marker: without anything reading it, the gates simply do not apply and
 * the endpoints work. The tests that verify the gate really EXEMPTS belong to
 * L4, together with the interceptor that makes it true.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipAccountGate {

    Gate[] value();

    enum Gate { ACCOUNT_STATUS, PASSWORD, ONBOARDING }
}
