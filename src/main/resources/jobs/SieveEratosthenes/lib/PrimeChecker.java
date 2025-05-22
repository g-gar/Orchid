import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PrimeChecker {

    // Una caché simple para evitar recalcular para el mismo número repetidamente.
    // Esto es opcional pero puede mejorar el rendimiento si se comprueban los mismos números muchas veces.
    // Para un sistema de producción con muchos números o muy grandes, considera una caché más robusta.
    private static final Map<Long, Boolean> primeCache = new ConcurrentHashMap<>();

    /**
     * Checks if a given number is prime.
     * Includes basic optimizations.
     *
     * @param number the number to check.
     * @return {@code true} if the number is prime, {@code false} otherwise.
     */
    public static boolean isPrime(long number) {
        if (number <= 1) {
            return false;
        }
        if (number <= 3) {
            return true; // 2 and 3 are prime
        }
        if (number % 2 == 0 || number % 3 == 0) {
            return false; // divisible by 2 or 3
        }

        // Check from 5 onwards, with a step of 6 (i, i+2)
        // (i.e., 6k ± 1 optimization)
        for (long i = 5; i * i <= number; i = i + 6) {
            if (number % i == 0 || number % (i + 2) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a given number is not prime.
     * This is the method intended for use in SpEL: T(com.ggar.orchid.util.PrimeChecker).isNotPrime(candidate)
     *
     * @param number the number to check.
     * @return {@code true} if the number is not prime, {@code false} if it is prime.
     */
    public static boolean isNotPrime(long number) {
        // Puedes usar la caché aquí si lo deseas, o directamente llamar a isPrime.
        // Ejemplo con caché:
        // return !primeCache.computeIfAbsent(number, PrimeChecker::isPrime);
        return !isPrime(number);
    }

    /**
     * Checks if a given number (passed as Object, common from SpEL) is not prime.
     * Handles potential type casting from SpEL.
     *
     * @param numberObject the number to check, expected to be a Number.
     * @return {@code true} if the number is not prime, {@code false} if it is prime or if input is invalid.
     */
    public static boolean isNotPrime(Object numberObject) {
        if (numberObject instanceof Number) {
            long number = ((Number) numberObject).longValue();
            return !isPrime(number);
        }
        // Si el objeto no es un número, podrías lanzar una excepción o devolver true/false
        // dependiendo de cómo quieras manejar entradas inválidas desde SpEL.
        // Devolver true (considerándolo "no primo" si no es un número válido) podría ser una opción segura.
        System.err.println("PrimeChecker.isNotPrime(Object) recibió un tipo no numérico: " + (numberObject != null ? numberObject.getClass().getName() : "null"));
        return true; // Considera no primo si la entrada no es un número válido
    }

    /**
     * Checks if a given number (passed as Object, common from SpEL) is prime.
     * Handles potential type casting from SpEL.
     *
     * @param numberObject the number to check, expected to be a Number.
     * @return {@code true} if the number is prime, {@code false} otherwise or if input is invalid.
     */
    public static boolean isPrime(Object numberObject) {
        if (numberObject instanceof Number) {
            long number = ((Number) numberObject).longValue();
            return isPrime(number);
        }
        System.err.println("PrimeChecker.isPrime(Object) recibió un tipo no numérico: " + (numberObject != null ? numberObject.getClass().getName() : "null"));
        return false; // Considera no primo si la entrada no es un número válido
    }


    // Método de ejemplo para limpiar la caché si fuera necesario (ej. en pruebas)
    public static void clearCache() {
        primeCache.clear();
    }
}
