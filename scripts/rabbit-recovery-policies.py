"""Apply dead-letter policies to existing queues without redeclaration or event loss.

Run before starting/restarting consumers. Defaults to local rabbitmqctl; use
--container NAME for the Compose broker. Each policy matches exactly one queue.
"""
import argparse
import json
import re
import subprocess

QUEUES = (
    "trade.seckill.order.q",
    "trade.fulfillment.status.q",
    "geek.inventory.payment-succeeded.v1",
)


def install_policies(container=None):
    prefix = ["docker", "exec", container] if container else []
    for index, queue in enumerate(QUEUES):
        subprocess.run(prefix + [
            "rabbitmqctl", "set_policy", "--apply-to", "queues", "--priority", "10",
            f"geek-recovery-{index}", "^" + re.escape(queue) + "$",
            json.dumps({"dead-letter-exchange": "geek.domain.events.dlx",
                        "dead-letter-routing-key": queue + ".dlq"}),
        ], check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container")
    args = parser.parse_args()
    install_policies(args.container)
