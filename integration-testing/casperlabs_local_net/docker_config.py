import os
from dataclasses import dataclass
from typing import Any, Optional
from docker import DockerClient


from casperlabs_local_net.casperlabs_accounts import Account
from casperlabs_local_net.common import random_string, BOOTSTRAP_PATH, testing_root_path


DEFAULT_NODE_ENV = {
    "RUST_BACKTRACE": "full",
    "CL_LOG_LEVEL": os.environ.get("CL_LOG_LEVEL", "INFO"),
    "CL_SERVER_NO_UPNP": "true",
    "CL_VERSION": "test",
}


@dataclass
class DockerConfig:
    """
    This holds all information that will be needed for creating both docker containers for a CL_Node
    """

    docker_client: DockerClient
    node_private_key: str
    node_public_key: str = None
    node_env: dict = None
    network: Optional[Any] = None
    number: int = 0
    rand_str: Optional[str] = None
    command_timeout: int = 180
    mem_limit: str = "4G"
    is_bootstrap: bool = False
    is_validator: bool = True
    is_signed_deploy: bool = True
    bootstrap_address: Optional[str] = None
    initial_motes: int = 100 * (10 ** 9)  # 100 billion
    socket_volume: Optional[str] = None
    node_account: Account = None
    grpc_encryption: bool = False
    is_read_only: bool = False

    def __post_init__(self):
        if self.rand_str is None:
            self.rand_str = random_string(5)
        if self.node_env is None:
            self.node_env = DEFAULT_NODE_ENV
        java_options = os.environ.get("_JAVA_OPTIONS")
        if java_options is not None:
            self.node_env["_JAVA_OPTIONS"] = java_options

    def tls_certificate_path(self):
        return f"{BOOTSTRAP_PATH}/node-{self.number}.certificate.pem"

    def tls_key_path(self):
        return f"{BOOTSTRAP_PATH}/node-{self.number}.key.pem"

    def tls_certificate_local_path(self):
        root_path = testing_root_path()
        return (
            root_path
            / "resources"
            / "bootstrap_certificate"
            / f"node-{self.number}.certificate.pem"
        )

    def node_command_options(self, server_host: str) -> dict:
        options = {
            "--server-default-timeout": "10second",
            "--server-host": server_host,
            "--grpc-socket": "/root/.casperlabs/sockets/.casper-node.sock",
            "--metrics-prometheus": "",
            "--tls-certificate": self.tls_certificate_path(),
            "--tls-key": self.tls_key_path(),
            "--tls-api-certificate": self.tls_certificate_path(),
            "--tls-api-key": self.tls_key_path(),
        }
        if not self.is_read_only:
            options["--casper-validator-private-key"] = self.node_private_key
        if self.grpc_encryption:
            options["--grpc-use-tls"] = ""
        if self.bootstrap_address:
            options["--server-bootstrap"] = self.bootstrap_address
        if self.node_public_key:
            options["--casper-validator-public-key"] = self.node_public_key
        return options
