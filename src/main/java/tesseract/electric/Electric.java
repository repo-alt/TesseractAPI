package tesseract.electric;

import tesseract.Constants;
import tesseract.electric.api.*;
import tesseract.graph.*;

/**
 * Class provides the functionality of a electricity with usage of graphs.
 * @apiNote default parameters are nonnull, methods return nonnull.
 */
public class Electric {

    private long position;
    private Producer producer;
    private Graph<IElectricCable, IElectricNode> graph;

    /**
     * Prevent the creation of empty handlers externally.
     *
     * @param dimension The dimension id where the node will be added.
     * @param position The position at which the node will be added.
     */
    private Electric(int dimension, long position) {
        this.graph = Constants.electric(dimension);
        this.position = position;
    }

    /**
     * @param dimension The dimension id where the node will be added.
     * @param position The position at which the node will be added.
     * @param node The node ref.
     * @return Create a instance of a class for a given producer/consumer node.
     */
    public static Electric ofProducer(int dimension, long position, IElectricNode node) {
        Electric system = new Electric(dimension, position);
        system.producer = new Producer(system.graph, node, system.position);
        system.graph.addNode(position, Connectivity.Cache.of(node, system.producer));
        return system;
    }

    /**
     * @param dimension The dimension id where the node will be added.
     * @param position The position at which the node will be added.
     * @param node The node ref.
     * @return Create a instance of a class for a given consumer node.
     */
    public static Electric ofConsumer(int dimension, long position, IElectricNode node) {
        Electric system = new Electric(dimension, position);
        system.graph.addNode(position, Connectivity.Cache.of(node));
        return system;
    }

    /**
     * @param dimension The dimension id where the cable will be added.
     * @param position The position at which the cable will be added.
     * @param cable The cable ref.
     * @return Create a instance of a class for a given cable connector.
     */
    public static Electric ofCable(int dimension, long position, IElectricCable cable) {
        Electric system = new Electric(dimension, position);
        system.graph.addConnector(position, Connectivity.Cache.of(cable));
        return system;
    }

    /**
     * Sends the energy to available consumers.
     */
    public void update() {
        if (producer.canOutput()) {
            long amps = producer.getOutputAmperage();
            for (Consumer consumer : producer.getConsumers()) {
                if (amps <= 0) break;

                if (consumer.isValid()) {
                    Packet required = consumer.getEnergyRequired(producer.getOutputVoltage());
                    long amperage = required.update(amps);

                    producer.extractEnergy(required);
                    consumer.insertEnergy(required);

                    amps = amperage;
                }
            }
        }
    }

    /**
     * Removes instance from the graph.
     */
    public void remove() {
        graph.removeAt(position);
    }
}
