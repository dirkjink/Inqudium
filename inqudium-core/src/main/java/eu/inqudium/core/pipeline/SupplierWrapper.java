package eu.inqudium.core.pipeline;

import java.util.function.Supplier;

/**
 * Eine homogene Implementierung des Wrappers für das Supplier-Interface.
 * * @param <T> Der Rückgabetyp des Suppliers.
 */
public class SupplierWrapper<T>
    extends BaseWrapper<Supplier<T>, T, SupplierWrapper<T>>
    implements Supplier<T> {

  /**
   * Erstellt einen neuen Supplier-Layer.
   *
   * @param name     Die fachliche Beschreibung dieser Schicht (z. B. "Cache-Layer").
   * @param delegate Der nächste innere Supplier oder ein Core-Lambda.
   */
  public SupplierWrapper(String name, Supplier<T> delegate) {
    super(name, delegate);
  }

  /**
   * Der öffentliche Einstiegspunkt gemäß java.util.function.Supplier.
   * Diese Methode initiiert die Kette immer am obersten (Outermost) Element.
   */
  @Override
  public T get() {
    // Nutzt die typsichere Methode der Basisklasse ohne Casts.
    return initiateChain();
  }

  /**
   * Führt die eigentliche Logik des eingewickelten Objekts aus.
   * Wird am Ende der Kette aufgerufen.
   */
  @Override
  protected T invokeCore() {
    return getDelegate().get();
  }

  /**
   * Ermöglicht es jeder Schicht, während des Durchlaufs auf die Call-ID zuzugreifen.
   * Da die ID als Parameter auf dem Stack liegt, ist dies absolut threadsicher.
   *
   * @param callId Die für diesen spezifischen Aufruf generierte ID.
   */
  @Override
  protected void handleLayer(String callId) {
    // Hier könnte schichtspezifisches Logging oder Monitoring erfolgen:
    // System.out.println("Processing " + getLayerDescription() + " with ID: " + callId);
  }
}