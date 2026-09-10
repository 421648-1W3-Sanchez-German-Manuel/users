package ar.edu.utn.frc.tup.p4.usersservice.shared.gates;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * DEC-14 - los gates son condiciones del ESTADO DE LA CUENTA, no del rol.
 * Meterlos en @PreAuthorize significaria repetir la misma condicion en cada
 * anotacion, y que cada controller conozca los estados.
 *
 * <p><b>Por que vive en la base y no en el lote que la implementa.</b> La
 * anotacion la ESCRIBEN tres lotes y la INTERPRETA uno solo:
 *
 * <ul>
 *   <li>L4 (T8) escribe el {@code AccountGateInterceptor}, que es quien la lee
 *       y decide. Esa es la logica, y sigue siendo de L4.</li>
 *   <li>L1 (T14, T15) la pone en logout, refresh y cambio de password.</li>
 *   <li>L6 la pone en los endpoints de onboarding.</li>
 * </ul>
 *
 * Una anotacion que tres lotes necesitan para COMPILAR es una costura, y las
 * costuras son de la base por definicion. Mientras vivio dentro de T8, L1 no
 * podia escribir una linea de T14 hasta que L4 mergeara: dos lotes de olas
 * distintas serializados por un archivo de seis lineas.
 *
 * <p><b>Sin el interceptor de L4 esto no exime de nada, y esta bien.</b> Es un
 * marcador: sin nadie que lo lea, los gates simplemente no se aplican y los
 * endpoints funcionan. Los tests que verifican que el gate EXIME de verdad son
 * de L4, junto con el interceptor que lo hace cierto.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipAccountGate {

    Gate[] value();

    enum Gate { ESTADO, PASSWORD, ONBOARDING }
}
