package tesseract.graph;

import tesseract.electric.api.IElectricCable;
import tesseract.electric.api.IElectricLimits;
import tesseract.electric.api.IElectricNode;
import tesseract.electric.api.IElectricStorage;
import tesseract.electric.base.ElectricLimits;
import tesseract.util.Dir;
import tesseract.util.Pos;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;

public class TestBench {

    public static void main(String[] args) throws Exception {

        Graph<ExampleCable, ExampleNode> graph = new Graph<>();

        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("> ");
            String line = input.readLine().trim();
            System.out.println(line);

            if (line.startsWith("add")) {
                String[] adds = line.split(" ");
                if (adds.length < 4) {
                    System.out.println("Usage: add <x> <y> <z> [node or connector]");
                    continue;
                }

                Pos pos = new Pos(Integer.parseInt(adds[1]), Integer.parseInt(adds[2]), Integer.parseInt(adds[3]));

                if (!graph.contains(pos)) {
                    if (adds.length == 5 && adds[4].startsWith("c")) {
                        graph.addConnector(pos, Connectivity.Cache.of(new ExampleCable()));
                    } else {
                        graph.addNode(pos, Connectivity.Cache.of(new ExampleNode()));
                    }

                    System.out.println("Added " + pos + " to the graph");
                } else {
                    System.out.println("Error: " + pos + " already exists in the graph");
                }

            } else if (line.startsWith("remove")) {
                String[] adds = line.split(" ");
                if (adds.length < 4) {
                    System.out.println("Usage: remove <x> <y> <z>");
                    continue;
                }

                Pos pos = new Pos(Integer.parseInt(adds[1]), Integer.parseInt(adds[2]), Integer.parseInt(adds[3]));

                Optional<Entry<ExampleCable, ExampleNode>> entry = graph.remove(pos);

                if (entry.isPresent()) {
                    entry.get().apply(
                        connector -> System.out.println("Removed connector " + pos + " from the graph: " + connector),
                        node -> System.out.println("Removed node " + pos + " from the graph: " + node)
                    );
                } else {
                    System.out.println("Error: " + pos + " doesn't exist in the graph");
                }

            } else if (line.startsWith("exit")) {
                return;
            }

            System.out.println("Graph contains " + graph.countGroups() + " groups:");

            graph.visit((groupId, group) -> {
                System.out.println("  Group " + groupId + " contains " + group.countBlocks() + " blocks: ");

                group.visitNodes((position, node) ->
                    System.out.println("    Node at " + position + ": " + node)
                );

                group.visitGrids(grid -> {
                    System.out.println("    Grid contains " + grid.countConnectors() + " connectors:");

                    grid.visitConnectors((position, connector) ->
                        System.out.println("      Connector at " + position + ": " + connector)
                    );
                });
            });
        }
    }

    private static class ExampleCable implements IElectricCable, IConnectable {

        @Override
        public long getLossPerBlock() {
            return 0;
        }

        @Override
        public IElectricLimits getPassageLimits() {
            return ElectricLimits.UNLIMITED;
        }

        @Override
        public String toString() {
            return "ExampleCable";
        }

        @Override
        public boolean connects(Dir direction) {
            return true;
        }
    }

    private static class ExampleNode implements IElectricNode, IConnectable {

        public IElectricStorage getStorage(Dir direction) {
            return null;
        }

        public IElectricLimits getReceiverLimits(Dir direction) {
            return null;
        }

        public int getOfferedPackets() {
            return 0;
        }

        @Override
        public String toString() {
            return "ExampleNode";
        }

        @Override
        public boolean connects(Dir direction) {
            return true;
        }
    }
}
