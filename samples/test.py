from springpython.jms.core import JmsTemplate
from springpython.jms.factory import WebSphereMQConnectionFactory

qm_name = "QM.1"
channel = "SVRCONN.1"
host = "192.168.1.121"
listener_port = "1434"
queue1 = "TEST.1"

# The connection factory we're going to use.
factory = WebSphereMQConnectionFactory(qm_name, channel, host, listener_port)

# Every JmsTemplate uses a connection factory for actually communicating with a JMS provider.
jms_template = JmsTemplate(factory)

# Get a message off the queue. The call to receive will by default time out
# after 1000ms and raise springpython.jms.NoMessageAvailableException then.
jms_template.receive(queue1)

# We're not using an IoC so we need to shut down the connection factory ourselves.
factory.destroy()