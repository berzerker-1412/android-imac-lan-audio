import Darwin

@main
struct ReceiverCLI {
    static func main() {
        exit(runReceiverCLI(arguments: CommandLine.arguments))
    }
}
