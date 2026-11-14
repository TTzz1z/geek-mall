"""Run real service processes against disposable MySQL/RabbitMQ and inject failures.

Build backend jars first. All fixtures live in task-owned containers. Results and
process logs go to ACCEPTANCE_TEMP_DIR (required on Windows).
"""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
MODULES = {
    "inventory": "geek-inventory-fulfillment", "member": "geek-member",
    "marketing": "geek-marketing", "settlement": "geek-settlement",
    "aftersale": "geek-aftersale-service", "product": "geek-merchant-product",
    "trade": "geek-trade",
}
DATABASES = {name: "recovery_" + name for name in MODULES}


def command(*args, **kwargs):
    return subprocess.check_output(list(args), text=True, encoding="utf-8", **kwargs).strip()


def free_port():
    with socket.socket() as connection:
        connection.bind(("127.0.0.1", 0))
        return connection.getsockname()[1]


def eventually(check, description, timeout=120):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, subprocess.CalledProcessError) as error:
            last = type(error).__name__
        time.sleep(1)
    raise AssertionError(f"Timed out waiting for {description} ({last})")


class RecoveryCheck:
    def __init__(self, directory):
        self.directory = directory
        self.prefix = "geek-recovery-" + secrets.token_hex(4)
        self.mysql = self.prefix + "-mysql"
        self.rabbit = self.prefix + "-rabbit"
        self.password = secrets.token_hex(16)
        self.key = secrets.token_hex(32)
        self.jwt_secret = secrets.token_hex(32)
        self.ports = {name: free_port() for name in MODULES}
        self.processes = {}
        self.logs = []
        self.containers = []

    def sql(self, service, statement):
        return command("docker", "exec", "-e", "MYSQL_PWD=" + self.password,
                       self.mysql, "mysql", "-uroot", "--batch", "--skip-column-names",
                       DATABASES[service], "-e", statement)

    def api(self, service, path, body=None, token=None, timeout=8):
        headers = {"Content-Type": "application/json", "X-Internal-Key": self.key}
        if token:
            headers["Authorization"] = "Bearer " + token
        payload = None if body is None else json.dumps(body).encode()
        request = Request(f"http://127.0.0.1:{self.ports[service]}{path}",
                          data=payload, headers=headers)
        with urlopen(request, timeout=timeout) as response:
            result = json.load(response)
        if "code" in result:
            assert result["code"] == "0", result
        return result.get("data", result)

    def token(self):
        def encode(value):
            return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=")
        now = int(time.time())
        message = encode({"alg": "HS256", "typ": "JWT"}) + b"." + encode({
            "iss": "geek-mall", "sub": "4", "aud": ["USER"], "iat": now,
            "exp": now + 3600, "username": "recovery", "roles": ["USER"],
            "permissions": [], "merchantId": 0, "shopId": 0})
        signature = hmac.new(self.jwt_secret.encode(), message, hashlib.sha256).digest()
        return (message + b"." + base64.urlsafe_b64encode(signature).rstrip(b"=")).decode()

    def start(self, name, paused_worker=False):
        module = MODULES[name]
        jar = ROOT / "backend" / module / "target" / f"{module}-0.1.0-SNAPSHOT.jar"
        staged_jar = self.directory / f"{name}-{len(self.logs)}.jar"
        shutil.copyfile(jar, staged_jar)
        environment = {**os.environ, "MYSQL_USER": "root", "MYSQL_PASSWORD": self.password,
                       "JWT_SECRET": self.jwt_secret, "INTERNAL_SERVICE_KEY": self.key,
                       "RABBITMQ_HOST": "127.0.0.1", "RABBITMQ_USER": "fixture",
                       "RABBITMQ_PASSWORD": self.password}
        for service in MODULES:
            environment[service.upper() + "_SERVICE_URL"] = f"http://127.0.0.1:{self.ports[service]}"
        java = os.environ.get("JAVA_HOME")
        executable = str(Path(java) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java else "java"
        log = open(self.directory / f"{name}-{len(self.logs)}.log", "w", encoding="utf-8")
        self.logs.append(log)
        process = subprocess.Popen([
            executable, "-Xmx192m", "-jar", str(staged_jar), f"--server.port={self.ports[name]}",
            f"--spring.datasource.url=jdbc:mysql://127.0.0.1:{self.mysql_port}/{DATABASES[name]}?allowPublicKeyRetrieval=true&useSSL=false",
            f"--spring.rabbitmq.port={self.rabbit_port}",
            "--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.discovery.enabled=false",
            "--spring.cloud.nacos.config.enabled=false", "--geek.outbox.interval=500",
            f"--geek.compensation.interval={3600000 if paused_worker else 500}",
            "--geek.refund.interval=500"], env=environment, stdout=log, stderr=subprocess.STDOUT)
        self.processes[name] = process
        eventually(lambda: process.poll() is None and self.api(name, "/actuator/health").get("status") == "UP",
                   name + " startup", 180)

    def stop(self, name):
        process = self.processes.pop(name, None)
        if process:
            process.kill()
            process.wait(timeout=30)

    def setup(self):
        self.mysql_port, self.rabbit_port = free_port(), free_port()
        for name, image, port, container_port, variables in [
            (self.mysql, "mysql:8.4", self.mysql_port, 3306, {"MYSQL_ROOT_PASSWORD": self.password}),
            (self.rabbit, "rabbitmq:4.0-management-alpine", self.rabbit_port, 5672,
             {"RABBITMQ_DEFAULT_USER": "fixture", "RABBITMQ_DEFAULT_PASS": self.password}),
        ]:
            arguments = ["docker", "run", "-d", "--rm", "--name", name,
                         "-p", f"127.0.0.1:{port}:{container_port}"]
            for key, value in variables.items():
                arguments.extend(["-e", key + "=" + value])
            command(*arguments, image)
            self.containers.append(name)
        eventually(lambda: command("docker", "exec", "-e", "MYSQL_PWD=" + self.password,
                                   self.mysql, "mysql", "-uroot", "-e", "SELECT 1") != "", "MySQL")
        eventually(lambda: command("docker", "exec", self.rabbit, "rabbitmq-diagnostics", "-q", "ping") != "", "RabbitMQ")
        command(os.sys.executable, str(ROOT / "scripts" / "rabbit-recovery-policies.py"), "--container", self.rabbit)
        for database in DATABASES.values():
            command("docker", "exec", "-e", "MYSQL_PWD=" + self.password, self.mysql,
                    "mysql", "-uroot", "-e", f"CREATE DATABASE {database} CHARACTER SET utf8mb4")
        for name in MODULES:
            self.start(name, paused_worker=name == "trade")

    def payment_recovery(self):
        self.sql("trade", """
            INSERT INTO trade_order(id,order_no,user_id,total_amount,payable_amount,status,checkout_token,inventory_status,created_at,updated_at)
            VALUES(7901,'GM-CRASH',4,100,100,'PENDING_PAYMENT','CRASH','RESERVED',NOW(),NOW());
            INSERT INTO trade_payment(id,payment_no,order_id,user_id,channel,merchant_no,amount,status,created_at,updated_at)
            VALUES(7902,'PAY-CRASH',7901,4,'SIMULATED','GEEK-MALL',100,'PENDING',NOW(),NOW());
            INSERT INTO trade_sub_order(id,sub_order_no,order_id,merchant_id,shop_id,shop_name,total_amount,discount_amount,payable_amount,status,created_at,updated_at)
            VALUES(7903,'GM-CRASH-1',7901,2001,3001,'fixture',100,0,100,'PENDING_PAYMENT',NOW(),NOW());
            INSERT INTO trade_order_item(id,order_id,sub_order_id,spu_id,sku_id,title,sku_snapshot,quantity,unit_price,discount_amount,payable_amount,created_at)
            VALUES(7904,7901,7903,1001,11001,'fixture',JSON_OBJECT(),1,100,0,100,NOW());
            INSERT INTO trade_fulfillment_plan(id,order_id,sub_order_id,warehouse_id,sku_id,quantity,status)
            VALUES(7905,7901,7903,9001,11001,1,'RESERVED');
            INSERT INTO trade_order_discount(id,order_id,discount_type,amount,snapshot_json)
            VALUES(7906,7901,'COUPON',10,JSON_OBJECT()),(7907,7901,'POINTS',1,JSON_OBJECT());
            """)
        self.api("marketing", "/api/v1/internal/marketing/coupons/freeze?orderNo=GM-CRASH&userId=4&userCouponId=71104&amount=10", {})
        self.api("member", "/api/v1/internal/member/points/GM-CRASH/freeze?userId=4&points=100", {})
        self.api("inventory", "/api/v1/internal/inventory/reservations", {
            "orderId": 7901, "orderNo": "GM-CRASH", "regionCode": "310000",
            "expiresAt": "2030-01-01T00:00:00Z", "items": [{"merchantId": 2001, "shopId": 3001, "skuId": 11001, "quantity": 1}]})
        self.api("trade", "/api/v1/payments/PAY-CRASH/simulate", {}, self.token())
        self.stop("trade")
        assert self.sql("trade", "SELECT status FROM trade_order WHERE id=7901") == "PAID"
        assert self.sql("trade", "SELECT status FROM trade_compensation_task WHERE business_no='GM-CRASH' AND task_type='SETTLE_ASSETS'") == "PENDING"
        assert self.sql("marketing", "SELECT status FROM marketing_coupon_reservation WHERE order_no='GM-CRASH'") == "FROZEN"
        assert self.sql("member", "SELECT status FROM member_point_reservation WHERE order_no='GM-CRASH'") == "FROZEN"
        self.start("trade")
        eventually(lambda: self.sql("inventory", "SELECT status FROM inventory_stock_reservation WHERE order_no='GM-CRASH'") == "DEDUCTED", "post-crash inventory deduction")
        eventually(lambda: self.sql("inventory", "SELECT COUNT(*) FROM inventory_fulfillment WHERE order_no='GM-CRASH'") == "1", "post-crash fulfillment")
        eventually(lambda: self.sql("marketing", "SELECT status FROM marketing_coupon_reservation WHERE order_no='GM-CRASH'") == "USED", "post-crash coupon use")
        eventually(lambda: self.sql("member", "SELECT status FROM member_point_reservation WHERE order_no='GM-CRASH'") == "USED", "post-crash points use")
        self.api("trade", "/api/v1/payments/PAY-CRASH/simulate", {}, self.token())
        assert self.sql("inventory", "SELECT SUM(quantity) FROM inventory_stock_reservation WHERE order_no='GM-CRASH' AND status='DEDUCTED'") == "1"

    def refund_recovery(self):
        self.stop("settlement")
        self.sql("aftersale", """
            INSERT INTO aftersale_order(id,aftersale_no,order_no,order_item_id,user_id,merchant_id,type,quantity,reason,status,requested_amount,approved_amount,snapshot_json,created_at,updated_at)
            VALUES(7910,'AS-CRASH','GM-CRASH',7904,4,2001,'REFUND_ONLY',1,'fixture','REFUNDING',100,100,JSON_OBJECT(),NOW(),NOW());
            """)
        eventually(lambda: self.sql("trade", "SELECT COUNT(*) FROM trade_refund WHERE aftersale_id='AS-CRASH'") == "1", "aftersale refund request")
        time.sleep(2)
        assert self.sql("trade", "SELECT status FROM trade_refund WHERE aftersale_id='AS-CRASH'") == "PROCESSING"
        self.start("settlement")
        eventually(lambda: self.sql("trade", "SELECT status FROM trade_refund WHERE aftersale_id='AS-CRASH'") == "SUCCESS", "refund recovery")
        eventually(lambda: self.sql("aftersale", "SELECT status FROM aftersale_order WHERE id=7910") == "COMPLETED", "aftersale completion")
        assert self.sql("settlement", "SELECT COUNT(*) FROM settlement_refund_application WHERE order_item_id=7904") == "1"

    def broker_recovery(self):
        command("docker", "pause", self.rabbit)
        try:
            result = self.api("marketing", "/api/v1/marketing/seckill/11001/orders", {
                "sessionId": 72001, "addressId": 81004, "quantity": 1}, self.token())
            order_id = result["seckillOrderId"]
            assert self.sql("marketing", f"SELECT COUNT(*) FROM marketing_event_outbox WHERE aggregate_id='{order_id}' AND status<>'PUBLISHED'") == "1"
        finally:
            command("docker", "unpause", self.rabbit)
        eventually(lambda: self.sql("marketing", f"SELECT status FROM marketing_event_outbox WHERE aggregate_id='{order_id}'") == "PUBLISHED", "marketing publish confirm")
        eventually(lambda: self.sql("trade", f"SELECT COUNT(*) FROM trade_seckill_progress WHERE order_no='SK{order_id}'") == "1", "durable trade intent")
        eventually(lambda: self.sql("marketing", f"SELECT status FROM marketing_seckill_order WHERE id={order_id}") == "CREATED", "seckill cross-service completion")

    def close(self):
        for name in list(self.processes):
            self.stop(name)
        for log in self.logs:
            log.close()
        for container in reversed(self.containers):
            subprocess.run(["docker", "rm", "-f", container], capture_output=True)


if __name__ == "__main__":
    root = os.environ.get("ACCEPTANCE_TEMP_DIR")
    if os.name == "nt" and not root:
        raise SystemExit("Set ACCEPTANCE_TEMP_DIR to a task directory under D:/Temp")
    directory = Path(root or tempfile.mkdtemp(prefix="geek-service-recovery-"))
    directory.mkdir(parents=True, exist_ok=True)
    check = RecoveryCheck(directory)
    results = []
    try:
        check.setup()
        for test in (check.payment_recovery, check.refund_recovery, check.broker_recovery):
            test()
            results.append({"case": test.__name__, "passed": True})
            print("PASS", test.__name__, flush=True)
    finally:
        check.close()
        (directory / "service-recovery.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
