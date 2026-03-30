package br.com.basa.pix.outbound.soap;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Data;

/**
 * Stub representando a classe gerada pelo JAXB a partir do WSDL do legado.
 * Em produção, esta classe seria gerada pelo jaxb2-maven-plugin.
 */
@Data
@XmlRootElement(name = "EfetuarTransferenciaResponse", namespace = "http://legado.basa.com.br/pix")
@XmlAccessorType(XmlAccessType.FIELD)
public class EfetuarTransferenciaResponse {
    private boolean sucesso;
    private String codigoRetorno;
    private String mensagem;
}
